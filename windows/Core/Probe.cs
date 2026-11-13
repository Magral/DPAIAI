using System.Net;
using System.Net.Http;
using System.Net.Security;

namespace DpiPilot;

public sealed record ProbeResult(bool Ok,int Passed,long Latency,string Detail,bool Inconclusive=false)
{
    public bool Complete => Ok && Passed==6 && Latency>0 && !Inconclusive;
}
public static class Probe
{
    public static async Task<ProbeResult> CheckAsync(EngineProcess engine,CancellationToken cancellation)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation); deadline.CancelAfter(TimeSpan.FromSeconds(150));
        int passed = 0, total = 0; var rows = new List<string>(); var times=new List<long>(); bool inconclusive=false;
        using var handler = new SocketsHttpHandler
        {
            UseProxy = false, AllowAutoRedirect = false, ConnectTimeout = TimeSpan.FromSeconds(45),
            ConnectCallback = async (context, token) => (await Socks.ConnectAsync(context.DnsEndPoint.Host,context.DnsEndPoint.Port,engine.Port,token)).GetStream(),
            MaxResponseHeadersLength = 32, MaxConnectionsPerServer = 1, PooledConnectionLifetime = TimeSpan.Zero
        };
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(55) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("DPI-Pilot-Windows/0.2.0");
        for (int round=0;round<2;round++)
        {
            foreach (var spec in new[] {"YouTube","Превью","CDN TLS"})
            {
                cancellation.ThrowIfCancellationRequested(); total++;
                var clock=System.Diagnostics.Stopwatch.StartNew();
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
                            if(response.StatusCode is HttpStatusCode.NotFound or HttpStatusCode.Gone or HttpStatusCode.TooManyRequests)inconclusive=true;
                            if (response.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.PartialContent) || response.Content.Headers.ContentType?.MediaType != "image/jpeg") throw new IOException("Preview response rejected");
                            using var body = await response.Content.ReadAsStreamAsync(deadline.Token); byte[] prefix = new byte[16]; await body.ReadExactlyAsync(prefix,deadline.Token);
                            if (prefix[0] != 255 || prefix[1] != 216 || prefix[2] != 255) throw new IOException("Preview signature rejected");
                        }
                    }
                    passed++;times.Add(Math.Max(1,clock.ElapsedMilliseconds));rows.Add(spec+" OK");
                }
                catch (Exception e) when (e is HttpRequestException or IOException or System.Security.Authentication.AuthenticationException or System.Net.Sockets.SocketException or OperationCanceledException)
                { cancellation.ThrowIfCancellationRequested();if(e is OperationCanceledException || deadline.IsCancellationRequested)inconclusive=true;rows.Add(spec+": "+e.GetType().Name); }
                if (deadline.IsCancellationRequested) break;
            }
            if (passed != (round+1)*3 || deadline.IsCancellationRequested) break;
        }
        return new(passed == 6,passed,times.Count==0 ? 0 : times.Order().ElementAt(times.Count/2),$"Успешно {passed}/6; проверено {total}/6: "+string.Join("; ",rows),inconclusive);
    }
}
