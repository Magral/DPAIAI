using System.Net.NetworkInformation;
using System.Runtime.InteropServices;

namespace DpiPilot;

/// Read-only baseline detection. Refuse existing manual/PAC/WPAD instead of changing other sites.
internal sealed class ProxySafety : IDisposable
{
    public bool Allowed { get; private set; }
    public string Reason { get; private set; } = "Проверяется исходная конфигурация прокси Windows";
    private int pending;
    private long checkedAt;
    public ProxySafety() { NetworkChange.NetworkAddressChanged += Changed; Check(); }
    private void Changed(object? sender,EventArgs args) { Interlocked.Exchange(ref checkedAt,0); Allowed=false; Check(); }
    public void Check()
    {
        if (!WinHttpGetIEProxyConfigForCurrentUser(out var config)) { Allowed=false; Reason="Не удалось прочитать системную конфигурацию; настройки Chrome не меняются"; return; }
        bool auto = config.AutoDetect;
        try
        {
            if (Marshal.PtrToStringUni(config.Proxy) is {Length:>0} || Marshal.PtrToStringUni(config.Url) is {Length:>0})
            { Allowed=false; Reason="В Windows уже задан прокси/PAC. Расширение не заменяет чужую конфигурацию."; return; }
        }
        finally { foreach (var p in new[] {config.Proxy,config.Bypass,config.Url}) if(p!=IntPtr.Zero) GlobalFree(p); }
        if (!auto) { Allowed=true; Reason="Исходное соединение DIRECT; другие сайты сохраняют прямой доступ"; return; }
        if (Environment.TickCount64-Interlocked.Read(ref checkedAt) < 15*60*1000 && Interlocked.Read(ref checkedAt)>0) return;
        if (Interlocked.Exchange(ref pending,1)!=0) return;
        Allowed=false; Reason="Проверяется отсутствие сетевого автопрокси WPAD…";
        _=Task.Run(()=>
        {
            IntPtr url=IntPtr.Zero;
            try
            {
                bool found=WinHttpDetectAutoProxyConfigUrl(3,out url); int error=Marshal.GetLastWin32Error();
                Allowed=!found && error==12180;
                Reason=Allowed ? "Системный автопрокси не обнаружен; другие адреса DIRECT" : "Обнаружен WPAD или его состояние неизвестно; настройки Chrome не меняются";
                Interlocked.Exchange(ref checkedAt,Environment.TickCount64);
            }
            finally { if(url!=IntPtr.Zero) GlobalFree(url); Interlocked.Exchange(ref pending,0); }
        });
    }
    public void Dispose() => NetworkChange.NetworkAddressChanged-=Changed;
    [StructLayout(LayoutKind.Sequential)] private struct Config { [MarshalAs(UnmanagedType.Bool)] public bool AutoDetect; public IntPtr Url,Proxy,Bypass; }
    [DllImport("winhttp.dll",SetLastError=true)] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool WinHttpGetIEProxyConfigForCurrentUser(out Config config);
    [DllImport("winhttp.dll",SetLastError=true)] [return:MarshalAs(UnmanagedType.Bool)] private static extern bool WinHttpDetectAutoProxyConfigUrl(uint flags,out IntPtr url);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalFree(IntPtr memory);
}
