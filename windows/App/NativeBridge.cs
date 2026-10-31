using System.Buffers.Binary;
using System.Diagnostics;
using System.IO.Pipes;
using System.Security.Principal;
using System.Text.Json;
using Microsoft.Win32;

namespace DpiPilot;

internal static class NativeBridge
{
    public const string ExtensionId="dfdbnmbhcnaojhfjfolfenecmmbiokhn";
    public const string Host="io.github.magral.dpipilot";
    public static string PipeName => "DpiPilot.YouTube."+WindowsIdentity.GetCurrent().User!.Value;
    public static void Register()
    {
        string file=Path.Combine(AppContext.BaseDirectory,"native-host.json");
        File.WriteAllText(file,JsonSerializer.Serialize(new {name=Host,description="DPI Pilot YouTube native component",path=Environment.ProcessPath,type="stdio",allowed_origins=new[]{"chrome-extension://"+ExtensionId+"/"}}));
        using var registry=Registry.CurrentUser.CreateSubKey(@"Software\Google\Chrome\NativeMessagingHosts\"+Host);
        registry.SetValue("",file);
    }
    public static async Task<byte[]?> ReadAsync(Stream input,CancellationToken cancellation)
    {
        byte[] header=new byte[4]; int first=await input.ReadAsync(header.AsMemory(0,1),cancellation); if(first==0) return null;
        await input.ReadExactlyAsync(header.AsMemory(1),cancellation); uint size=BinaryPrimitives.ReadUInt32LittleEndian(header);
        if(size is 0 or >65536) throw new InvalidDataException("Message length");
        byte[] body=new byte[size]; await input.ReadExactlyAsync(body,cancellation); return body;
    }
    public static async Task WriteAsync(Stream output,byte[] data,CancellationToken cancellation)
    { byte[] header=new byte[4]; BinaryPrimitives.WriteUInt32LittleEndian(header,(uint)data.Length); await output.WriteAsync(header,cancellation); await output.WriteAsync(data,cancellation); await output.FlushAsync(cancellation); }
    private static bool SavedIntent()
    {
        try {using var json=JsonDocument.Parse(File.ReadAllText(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"DPI-Pilot-YouTube","settings.json")));return json.RootElement.GetProperty("Enabled").GetBoolean();}
        catch(Exception){return false;}
    }
    public static async Task RunHostAsync()
    {
        using var input=Console.OpenStandardInput(); using var output=Console.OpenStandardOutput();
        while(true)
        {
            byte[]? message=await ReadAsync(input,CancellationToken.None); if(message is null) return;
            using var json=JsonDocument.Parse(message); var root=json.RootElement;
            string command=root.GetProperty("command").GetString() ?? "";
            bool launch=command is "enable" or "openApp" || command=="hello" && root.TryGetProperty("launch",out var flag) && flag.GetBoolean();
            byte[] response; bool offline=false;
            try
            {
                using var pipe=new NamedPipeClientStream(".",PipeName,PipeDirection.InOut,PipeOptions.Asynchronous|PipeOptions.CurrentUserOnly);
                try { await pipe.ConnectAsync(700); }
                catch(TimeoutException) when(launch)
                {
                    Process.Start(new ProcessStartInfo(Environment.ProcessPath!) {UseShellExecute=false,ArgumentList={"--background"}});
                    await pipe.ConnectAsync(30000);
                }
                await WriteAsync(pipe,message,CancellationToken.None);
                response=await ReadAsync(pipe,CancellationToken.None) ?? throw new EndOfStreamException();
            }
            catch(Exception e) when(e is IOException or TimeoutException or UnauthorizedAccessException)
            { offline=true; response=JsonSerializer.SerializeToUtf8Bytes(new {running=false,enabled=false,desiredEnabled=SavedIntent(),phase="Windows-компонент не запущен",routingAllowed=false,routingReason="Нажмите «Включить» или откройте DPI Pilot"}); }
            await WriteAsync(output,response,CancellationToken.None);
            if(offline)return;
        }
    }
    public static async Task ServerAsync(Func<string,Task<object>> command,CancellationToken cancellation)
    {
        while(!cancellation.IsCancellationRequested)
        {
            var pipe=new NamedPipeServerStream(PipeName,PipeDirection.InOut,8,PipeTransmissionMode.Byte,PipeOptions.Asynchronous|PipeOptions.CurrentUserOnly);
            try { await pipe.WaitForConnectionAsync(cancellation); }
            catch { pipe.Dispose(); if(cancellation.IsCancellationRequested)return; throw; }
            _=ServeAsync(pipe,command,cancellation);
        }
    }
    private static async Task ServeAsync(NamedPipeServerStream pipe,Func<string,Task<object>> command,CancellationToken cancellation)
    {
        await using(pipe)
        using(var timeout=CancellationTokenSource.CreateLinkedTokenSource(cancellation))
        {
            timeout.CancelAfter(TimeSpan.FromSeconds(40));
            try
            {
                byte[]? request=await ReadAsync(pipe,timeout.Token); if(request is null)return;
                using var json=JsonDocument.Parse(request); string name=json.RootElement.GetProperty("command").GetString() ?? "";
                if(!new[]{"hello","status","enable","disable","check","refresh","openApp","shutdown"}.Contains(name)) throw new InvalidDataException("Command");
                object response=await command(name); await WriteAsync(pipe,JsonSerializer.SerializeToUtf8Bytes(response),timeout.Token);
            }
            catch(Exception e) when(e is IOException or OperationCanceledException or JsonException or KeyNotFoundException) { }
        }
    }
}
