using System.Net;
using System.Net.Http;
using System.Net.Security;

namespace DpiPilot;

public sealed record ProbeResult(bool Ok,int Passed,long Latency,string Detail);
public static class Probe
{
    public static async Task<ProbeResult> CheckAsync(EngineProcess engine,CancellationToken cancellation)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation); deadline.CancelAfter(TimeSpan.FromSeconds(150));
        int passed = 0, total = 0; var rows = new List<string>(); var clock = System.Diagnostics.Stopwatch.StartNew();
        using var handler = new SocketsHttpHandler
        {
            UseProxy = false, AllowAutoRedirect = false, ConnectTimeout = TimeSpan.FromSeconds(45),
            ConnectCallback = async (context, token) => (await Socks.ConnectAsync(context.DnsEndPoint.Host,context.DnsEndPoint.Port,engine.Port,token)).GetStream(),
            MaxResponseHeadersLength = 32, MaxConnectionsPerServer = 1, PooledConnectionLifetime = TimeSpan.Zero
        };
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(55) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("DPI-Pilot-Windows/0.1.0");
        for (int round=0;round<2;round++)
        {
            foreach (var spec in new[] {"YouTube","Превью","CDN TLS"})
            {
                cancellation.ThrowIfCancellationRequested(); total++;
                try
                {
                    if (spec == "CDN TLS")
                    {
                        using var socket = await Socks.ConnectAsync("redirector.googlevideo.com",443,engine.Port,deadline.Token);
                        using var tls = new SslStream(socket.GetStream());
                        await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = "redirector.googlevideo.com" },deadline.Token);
                    }
                    else
                    {
                        var uri = spec == "YouTube" ? "https://www.youtube.com/generate_204" : "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg";
                        using var request = new HttpRequestMessage(HttpMethod.Get,uri) { Version = HttpVersion.Version11, VersionPolicy = HttpVersionPolicy.RequestVersionExact };
                        request.Headers.ConnectionClose = true;
                        if (spec == "Превью") request.Headers.Range = new(0,1023);
                        using var response = await http.SendAsync(request,HttpCompletionOption.ResponseHeadersRead,deadline.Token);
                        if (spec == "YouTube") { if (response.StatusCode != HttpStatusCode.NoContent) throw new IOException("HTTP "+(int)response.StatusCode); }
                        else
                        {
                            if (response.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.PartialContent) || response.Content.Headers.ContentType?.MediaType != "image/jpeg") throw new IOException("Preview response rejected");
                            using var body = await response.Content.ReadAsStreamAsync(deadline.Token); byte[] prefix = new byte[16]; await body.ReadExactlyAsync(prefix,deadline.Token);
                            if (prefix[0] != 255 || prefix[1] != 216 || prefix[2] != 255) throw new IOException("Preview signature rejected");
                        }
                    }
                    passed++; rows.Add(spec+" OK");
                }
                catch (Exception e) when (e is HttpRequestException or IOException or System.Security.Authentication.AuthenticationException or System.Net.Sockets.SocketException or OperationCanceledException)
                { cancellation.ThrowIfCancellationRequested(); rows.Add(spec+": "+e.GetType().Name); }
                if (deadline.IsCancellationRequested) break;
            }
            if (passed != 3 || deadline.IsCancellationRequested) break;
        }
        return new(passed == 6,passed,clock.ElapsedMilliseconds/Math.Max(1,total),$"{passed}/{total}: "+string.Join("; ",rows));
    }
}
