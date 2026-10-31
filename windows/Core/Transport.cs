using System.Net;
using System.Net.Sockets;
using System.Text;

namespace DpiPilot;

public static class Socks
{
    public static async Task<TcpClient> ConnectAsync(string host, int port, int proxyPort, CancellationToken cancellation)
    {
        var client = new TcpClient { NoDelay = true };
        try
        {
            await client.ConnectAsync(IPAddress.Loopback, proxyPort, cancellation);
            var stream = client.GetStream(); await stream.WriteAsync(new byte[] { 5,1,0 },cancellation);
            var greeting = new byte[2]; await stream.ReadExactlyAsync(greeting,cancellation);
            if (greeting[0] != 5 || greeting[1] != 0) throw new IOException("SOCKS authentication rejected");
            byte[] domain = Encoding.ASCII.GetBytes(host);
            if (domain.Length is < 1 or > 253) throw new IOException("SOCKS hostname length");
            await stream.WriteAsync(new byte[] {5,1,0,3,(byte)domain.Length}.Concat(domain).Concat(new byte[] {(byte)(port>>8),(byte)port}).ToArray(),cancellation);
            var reply = new byte[4]; await stream.ReadExactlyAsync(reply,cancellation);
            if (reply[0] != 5 || reply[1] != 0) throw new IOException("SOCKS upstream connection failed ("+reply[1]+")");
            int length = reply[3] switch { 1=>4, 4=>16, 3=>await ReadByteAsync(stream,cancellation), _=>throw new IOException("SOCKS address type") };
            await stream.ReadExactlyAsync(new byte[length+2],cancellation);
            return client;
        }
        catch { client.Dispose(); throw; }
    }
    public static async Task<int> ReadByteAsync(Stream stream,CancellationToken cancellation)
    { byte[] b = new byte[1]; await stream.ReadExactlyAsync(b,cancellation); return b[0]; }
}

/// Only loopback, only CONNECT to approved YouTube hostnames on TCP/443. No UDP association.
public sealed class LocalGateway : IAsyncDisposable
{
    private readonly TcpListener proxy, pac;
    private readonly CancellationTokenSource lifetime = new();
    private readonly Func<string,int,CancellationToken,Task<ConnectionRoute>> connect;
    private readonly string pacPath;
    private readonly SemaphoreSlim connections = new(128);
    private readonly System.Collections.Concurrent.ConcurrentDictionary<long,CancellationTokenSource> clients = new();
    private long serial, sent, received, accepted, rejected;
    private readonly Action<string> log;
    public int ProxyPort => ((IPEndPoint)proxy.LocalEndpoint).Port;
    public int PacPort => ((IPEndPoint)pac.LocalEndpoint).Port;
    public string PacUrl => $"http://127.0.0.1:{PacPort}/{pacPath}";
    public long Sent => Interlocked.Read(ref sent);
    public long Received => Interlocked.Read(ref received);
    public long Accepted => Interlocked.Read(ref accepted);
    public long Rejected => Interlocked.Read(ref rejected);
    public int Active => clients.Count;
    public LocalGateway(int proxyPort,int pacPort,string token,Func<string,int,CancellationToken,Task<ConnectionRoute>> connect,Action<string> log)
    {
        if (token.Length != 40 || token.Any(c => !char.IsAsciiHexDigit(c))) throw new ArgumentException("PAC token");
        this.connect = connect; this.log = log; pacPath = token+"/youtube.pac";
        proxy = new(IPAddress.Loopback,proxyPort); pac = new(IPAddress.Loopback,pacPort);
        try { proxy.Start(128); pac.Start(16); }
        catch { proxy.Stop(); pac.Stop(); throw; }
        _ = AcceptAsync(proxy,HandleProxyAsync); _ = AcceptAsync(pac,HandlePacAsync);
    }
    private async Task AcceptAsync(TcpListener listener,Func<TcpClient,Task> handle)
    {
        try { while (!lifetime.IsCancellationRequested) { var client = await listener.AcceptTcpClientAsync(lifetime.Token); _ = handle(client); } }
        catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException or SocketException) { }
    }
    private async Task HandlePacAsync(TcpClient client)
    {
        using (client)
        using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token))
        {
            timeout.CancelAfter(TimeSpan.FromSeconds(5));
            try
            {
                var input = client.GetStream(); var bytes = new List<byte>(); var one = new byte[1];
                while (bytes.Count < 4096) { await input.ReadExactlyAsync(one,timeout.Token); bytes.Add(one[0]); if (bytes.Count >= 4 && bytes.TakeLast(4).SequenceEqual(new byte[] {13,10,13,10})) break; }
                string request = Encoding.ASCII.GetString(bytes.ToArray());
                bool valid = request.StartsWith("GET /"+pacPath+" HTTP/1.",StringComparison.Ordinal) && request.Split("\r\n").Any(line => line.Equals($"Host: 127.0.0.1:{PacPort}",StringComparison.OrdinalIgnoreCase));
                byte[] body = Encoding.ASCII.GetBytes(valid ? DomainPolicy.Pac(ProxyPort) : "Not found");
                byte[] header = Encoding.ASCII.GetBytes($"HTTP/1.1 {(valid ? "200 OK" : "404 Not Found")}\r\nContent-Type: application/x-ns-proxy-autoconfig\r\nContent-Length: {body.Length}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
                await input.WriteAsync(header,timeout.Token); await input.WriteAsync(body,timeout.Token);
            }
            catch (Exception e) when (e is IOException or OperationCanceledException or SocketException) { }
        }
    }
    private async Task HandleProxyAsync(TcpClient client)
    {
        using (client)
        using (var token = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token))
        {
            if (!await connections.WaitAsync(0)) return;
            long number = Interlocked.Increment(ref serial); clients[number] = token;
            bool established = false;
            try
            {
                client.NoDelay = true; var input = client.GetStream();
                using var handshake = CancellationTokenSource.CreateLinkedTokenSource(token.Token); handshake.CancelAfter(TimeSpan.FromSeconds(50));
                byte[] greeting = new byte[2]; await input.ReadExactlyAsync(greeting,handshake.Token);
                if (greeting[0] != 5 || greeting[1] == 0) return;
                var methods = new byte[greeting[1]]; await input.ReadExactlyAsync(methods,handshake.Token);
                if (!methods.Contains((byte)0)) { await input.WriteAsync(new byte[] {5,255},handshake.Token); return; }
                await input.WriteAsync(new byte[] {5,0},handshake.Token);
                byte[] header = new byte[4]; await input.ReadExactlyAsync(header,handshake.Token);
                if (header[0] != 5 || header[1] != 1 || header[2] != 0 || header[3] != 3) { Interlocked.Increment(ref rejected); await ReplyAsync(input,2,handshake.Token); return; }
                int count = await Socks.ReadByteAsync(input,handshake.Token); if (count is < 1 or > 253) return;
                byte[] hostBytes = new byte[count]; await input.ReadExactlyAsync(hostBytes,handshake.Token);
                byte[] portBytes = new byte[2]; await input.ReadExactlyAsync(portBytes,handshake.Token);
                string host = DomainPolicy.Normalize(Encoding.ASCII.GetString(hostBytes)); int port = portBytes[0]*256+portBytes[1];
                if (!DomainPolicy.Allows(host,port)) { Interlocked.Increment(ref rejected); await ReplyAsync(input,2,handshake.Token); return; }
                Interlocked.Increment(ref accepted);
                using var route = await connect(host,port,handshake.Token);
                using var remote = route.Client; var upstream = remote.GetStream();
                await ReplyAsync(input,0,handshake.Token); established = true;
                Task tx = PumpAsync(input,upstream,n => Interlocked.Add(ref sent,n),token.Token);
                Task rx = PumpAsync(upstream,input,n => Interlocked.Add(ref received,n),token.Token);
                // A clean half-close must not truncate the remaining server reply.
                var first = await Task.WhenAny(tx,rx);
                if (first.IsFaulted || first.IsCanceled) token.Cancel();
                else if (first == tx) remote.Client.Shutdown(SocketShutdown.Send);
                else client.Client.Shutdown(SocketShutdown.Send);
                token.CancelAfter(TimeSpan.FromMinutes(2));
                using var drain = token.Token.Register(() => { client.Dispose(); remote.Dispose(); });
                await Task.WhenAll(tx,rx);
            }
            catch (Exception e) when (e is IOException or SocketException or OperationCanceledException or ObjectDisposedException)
            { if (!established && !token.IsCancellationRequested) log("Соединение YouTube: "+e.GetType().Name); }
            finally { clients.TryRemove(number,out _); connections.Release(); }
        }
    }
    private static Task ReplyAsync(Stream stream,byte code,CancellationToken cancellation) => stream.WriteAsync(new byte[] {5,code,0,1,127,0,0,1,0,0},cancellation).AsTask();
    private static async Task PumpAsync(Stream input,Stream output,Action<int> count,CancellationToken cancellation)
    { byte[] buffer = new byte[65536]; while (true) { int read = await input.ReadAsync(buffer,cancellation); if (read == 0) return; await output.WriteAsync(buffer.AsMemory(0,read),cancellation); count(read); } }
    public void DropYouTubeConnections() { foreach (var source in clients.Values) try { source.Cancel(); } catch (ObjectDisposedException) { } }
    public ValueTask DisposeAsync() { lifetime.Cancel(); proxy.Stop(); pac.Stop(); DropYouTubeConnections(); return ValueTask.CompletedTask; }
}

public sealed class ConnectionRoute(TcpClient client,IDisposable? lease = null) : IDisposable
{
    public TcpClient Client { get; } = client;
    public void Dispose() { Client.Dispose(); lease?.Dispose(); }
}
