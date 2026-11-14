using System.Net.NetworkInformation;

namespace DpiPilot;

/// A conservative run-scoped guard: never mix URLs discovered before a visible network/VPN change.
internal sealed class PlaybackRouteGuard : IDisposable
{
    private readonly string initial="";
    private int changed;
    private readonly bool watching=OperatingSystem.IsWindows();
    public PlaybackRouteGuard()
    {
        if(!watching)return;
        initial=Snapshot();
        NetworkChange.NetworkAddressChanged+=Changed;
        NetworkChange.NetworkAvailabilityChanged+=AvailabilityChanged;
    }
    private static string Snapshot()=>string.Join('|',NetworkInterface.GetAllNetworkInterfaces()
        .Where(n=>n.OperationalStatus==OperationalStatus.Up && n.NetworkInterfaceType!=NetworkInterfaceType.Loopback)
        .Select(n=> {var p=n.GetIPProperties();return n.Id+":"+string.Join(',',p.UnicastAddresses.Select(a=>a.Address.ToString()).Order())+":"+string.Join(',',p.GatewayAddresses.Select(a=>a.Address.ToString()).Order())+":"+string.Join(',',p.DnsAddresses.Select(a=>a.ToString()).Order());}).Order());
    private void Changed(object? sender,EventArgs args)=>Interlocked.Exchange(ref changed,1);
    private void AvailabilityChanged(object? sender,NetworkAvailabilityEventArgs args)=>Interlocked.Exchange(ref changed,1);
    public void Check()
    {
        if(watching && (Volatile.Read(ref changed)!=0 || Snapshot()!=initial))throw new OperationCanceledException("Network route changed; diagnostic invalidated");
    }
    public void Dispose()
    {
        if(!watching)return;
        NetworkChange.NetworkAddressChanged-=Changed;
        NetworkChange.NetworkAvailabilityChanged-=AvailabilityChanged;
    }
}
