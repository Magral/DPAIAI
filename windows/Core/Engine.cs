using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace DpiPilot;

/// Closing the app or its Windows job kills only our native children, never Chrome or a game.
public sealed class NativeJob : IDisposable
{
    private readonly IntPtr handle;
    private int closed;
    public NativeJob()
    {
        if (!OperatingSystem.IsWindows()) return;
        handle = CreateJobObject(IntPtr.Zero,null); if (handle == IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
        var limits = new ExtendedLimits { Basic = new BasicLimits { LimitFlags = 0x2000 } };
        int size = Marshal.SizeOf<ExtendedLimits>(); IntPtr memory = Marshal.AllocHGlobal(size);
        try { Marshal.StructureToPtr(limits,memory,false); if (!SetInformationJobObject(handle,9,memory,(uint)size)) throw new System.ComponentModel.Win32Exception(); }
        catch { CloseHandle(handle); throw; }
        finally { Marshal.FreeHGlobal(memory); }
    }
    public void Add(Process process)
    { if (handle != IntPtr.Zero && !AssignProcessToJobObject(handle,process.Handle)) { process.Kill(true); throw new System.ComponentModel.Win32Exception(); } }
    public void KillChildren() { if (handle != IntPtr.Zero) TerminateJobObject(handle,0); }
    public void Dispose() { if (Interlocked.Exchange(ref closed,1)==0 && handle != IntPtr.Zero) CloseHandle(handle); }
    [DllImport("kernel32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool TerminateJobObject(IntPtr job,uint code);
    [StructLayout(LayoutKind.Sequential)] private struct BasicLimits { public long PerProcess,PerJob; public uint LimitFlags; public UIntPtr Minimum,Maximum; public uint Active; public UIntPtr Affinity; public uint Priority,Scheduling; }
    [StructLayout(LayoutKind.Sequential)] private struct IoCounters { public ulong A,B,C,D,E,F; }
    [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits { public BasicLimits Basic; public IoCounters Io; public UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob; }
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] private static extern IntPtr CreateJobObject(IntPtr attributes,string? name);
    [DllImport("kernel32.dll",SetLastError=true)] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool SetInformationJobObject(IntPtr job,int info,IntPtr data,uint length);
    [DllImport("kernel32.dll",SetLastError=true)] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll")] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool CloseHandle(IntPtr handle);
}

public sealed class EngineProcess : IDisposable
{
    private readonly object gate = new();
    private readonly Process process;
    private bool retired,disposed;
    private int users;
    public int Port { get; }
    public Strategy Strategy { get; }
    public bool Alive { get { lock(gate) return !disposed && !process.HasExited; } }
    private EngineProcess(Process process,int port,Strategy strategy) { this.process = process; Port = port; Strategy = strategy; }
    public static async Task<EngineProcess> StartAsync(string executable,Strategy strategy,NativeJob job,CancellationToken cancellation)
    {
        if (!CatalogProtocol.ValidCommand(strategy.Args) || !CatalogProtocol.WindowsCompatible(strategy)) throw new InvalidDataException("Unsupported engine command");
        var reservation = new TcpListener(IPAddress.Loopback,0); reservation.Start(); int port = ((IPEndPoint)reservation.LocalEndpoint).Port; reservation.Stop();
        var info = new ProcessStartInfo(executable) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = Path.GetDirectoryName(executable)! };
        foreach (string arg in new[] {"--ip","127.0.0.1","--port",port.ToString()}.Concat(strategy.Args.Split(' ',StringSplitOptions.RemoveEmptyEntries))) info.ArgumentList.Add(arg);
        // No source text is a shell command. Environment does not carry CI/signing credentials.
        foreach (var name in info.Environment.Keys.Where(k => k.Contains("TOKEN",StringComparison.OrdinalIgnoreCase) || k.Contains("PASSWORD",StringComparison.OrdinalIgnoreCase) || k.Contains("SIGNING",StringComparison.OrdinalIgnoreCase)).ToArray()) info.Environment.Remove(name);
        var process = Process.Start(info) ?? throw new IOException("Engine process not created");
        var engine = new EngineProcess(process,port,strategy);
        try
        {
            job.Add(process);
            process.OutputDataReceived += (_,_) => { }; process.ErrorDataReceived += (_,_) => { }; process.BeginOutputReadLine(); process.BeginErrorReadLine();
            for (int i=0;i<70;i++)
            {
                cancellation.ThrowIfCancellationRequested(); if (process.HasExited) throw new IOException("Native parser rejected Windows strategy");
                try
                {
                    using var client = new TcpClient(); await client.ConnectAsync(IPAddress.Loopback,port,cancellation);
                    var stream = client.GetStream(); await stream.WriteAsync(new byte[] {5,1,0},cancellation); var reply = new byte[2];
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation); timeout.CancelAfter(500);
                    await stream.ReadExactlyAsync(reply,timeout.Token); if (reply.SequenceEqual(new byte[] {5,0})) return engine;
                }
                catch (Exception e) when (e is SocketException or IOException or OperationCanceledException) { cancellation.ThrowIfCancellationRequested(); }
                await Task.Delay(50,cancellation);
            }
            throw new IOException("Engine startup timeout");
        }
        catch { engine.Dispose(); throw; }
    }
    public IDisposable? Acquire()
    { lock (gate) { if (retired || disposed || !Alive) return null; users++; return new Lease(this); } }
    public void Retire() { lock (gate) { retired = true; if (users == 0) Dispose(); } }
    private void Release() { lock (gate) { users--; if (users == 0 && retired) Dispose(); } }
    private sealed class Lease(EngineProcess owner) : IDisposable
    { private EngineProcess? current = owner; public void Dispose() => Interlocked.Exchange(ref current,null)?.Release(); }
    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return; disposed = true; retired = true;
            try { if (!process.HasExited) { process.Kill(true); process.WaitForExit(3000); } }
            finally { process.Dispose(); }
        }
    }
}
