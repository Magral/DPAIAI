using DpiPilot;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;

static void Assert(bool condition,string message){if(!condition)throw new Exception(message);}
string RegistrySnapshot()
{
    if(!OperatingSystem.IsWindows())return "";
    using var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings");
    using var connection=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings\Connections");
    using var winhttp=Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Internet Settings\Connections");
    var values=new Dictionary<string,string?>();
    foreach(var name in new[]{"ProxyEnable","ProxyServer","AutoConfigURL","AutoDetect"})values[name]=key?.GetValue(name)?.ToString();
    foreach(var name in new[]{"DefaultConnectionSettings","SavedLegacySettings"})values[name]=connection?.GetValue(name) is byte[] bytes ? Convert.ToHexString(bytes) : null;
    values["WinHttpSettings"]=winhttp?.GetValue("WinHttpSettings") is byte[] body ? Convert.ToHexString(body) : null;
    return JsonSerializer.Serialize(values);
}
string registryBefore=RegistrySnapshot();
string resources=Path.GetFullPath(args[0]);string native=Path.Combine(resources,"ciadpi.exe");
using var cert=X509Certificate2.CreateFromPem(File.ReadAllText(Path.Combine(resources,"catalog-cert.pem")));
using var publicKey=cert.GetRSAPublicKey()!;
byte[] feed=File.ReadAllBytes(Path.Combine(resources,"catalog-v1.json"));
var all=CatalogProtocol.Verify(feed,publicKey,0);var bank=all.Entries.Where(CatalogProtocol.WindowsCompatible).ToArray();
Assert(bank.Length>=60,"Windows bank too small");
Assert(CatalogProtocol.Id("")=="e3b0c44298fc1c14","Stable identity mismatch");
foreach(string domain in new[]{"youtube.com","www.youtube.com","r2---sn-a.googlevideo.com","i.ytimg.com","youtubei.googleapis.com"})Assert(DomainPolicy.Allows(domain),"YouTube host rejected");
foreach(string domain in new[]{"youtube.com.evil.test","notyoutube.com","google.com","accounts.google.com","maps.googleapis.com","steamcommunity.com","127.0.0.1","localhost","evilgooglevideo.com"})Assert(!DomainPolicy.Allows(domain),"Unrelated host routed");
Assert(!DomainPolicy.Allows("youtube.com",80),"Non-TLS port routed");
foreach(string command in new[]{"--ip 0.0.0.0","--fake-data file","--port 1080","--split 1; whoami","--udp-fake 999","--timeout 99999"})Assert(!CatalogProtocol.ValidCommand(command),"Unsafe command accepted");
foreach(var malformed in new[]{"{}","{\"payload\":null}","{\"payload\":\"!\",\"signature\":\"!\"}"})
{try{CatalogProtocol.Verify(Encoding.UTF8.GetBytes(malformed),publicKey,0);throw new Exception("Malformed feed accepted");}catch(Exception e)when(e is InvalidDataException or FormatException or CryptographicException) {}}
try{CatalogProtocol.Verify(feed,publicKey,all.Sequence+1);throw new Exception("Rollback accepted");}catch(InvalidDataException){}
using(var key=RSA.Create(2048))
{
    try{CatalogProtocol.Verify(feed,key,0);throw new Exception("Foreign key accepted");}catch(CryptographicException){}
    var envelope=JsonDocument.Parse(feed).RootElement;byte[] payload=Convert.FromBase64String(envelope.GetProperty("payload").GetString()!);payload[^1]^=1;
    byte[] tampered=JsonSerializer.SerializeToUtf8Bytes(new{payload=Convert.ToBase64String(payload),signature=envelope.GetProperty("signature").GetString()});
    try{CatalogProtocol.Verify(tampered,publicKey,0);throw new Exception("Tampered data accepted");}catch(CryptographicException){}
}
Console.WriteLine($"Signed actual catalog: {bank.Length}/{all.Entries.Count} compatible; tamper/rollback/resource/domain tests passed");

using var lifetime=new CancellationTokenSource(TimeSpan.FromMinutes(12));
using var job=new NativeJob();
foreach(var strategy in bank)
{using var engine=await EngineProcess.StartAsync(native,strategy,job,lifetime.Token);}
Console.WriteLine("Every Windows-compatible command started and negotiated SOCKS5 with actual Windows engine");

var echo=new TcpListener(IPAddress.Loopback,0);echo.Start();int echoPort=((IPEndPoint)echo.LocalEndpoint).Port;
_=Task.Run(async()=>{while(!lifetime.IsCancellationRequested){TcpClient client;try{client=await echo.AcceptTcpClientAsync(lifetime.Token);}catch(OperationCanceledException){break;}_=Task.Run(async()=>{using(client){var stream=client.GetStream();byte[] buffer=new byte[100];try{while(true){int n=await stream.ReadAsync(buffer,lifetime.Token);if(n==0)break;await stream.WriteAsync(buffer.AsMemory(0,n),lifetime.Token);}}catch(IOException){}}});}});
async Task Ping(TcpClient client,string text){var b=Encoding.UTF8.GetBytes(text);await client.GetStream().WriteAsync(b,lifetime.Token);byte[] reply=new byte[b.Length];await client.GetStream().ReadExactlyAsync(reply,lifetime.Token);Assert(reply.SequenceEqual(b),"Existing TCP stream damaged");}
using(var game=new TcpClient())
using(var active=await EngineProcess.StartAsync(native,bank[1],job,lifetime.Token))
using(var lease=active.Acquire())
using(var flow=await Socks.ConnectAsync("127.0.0.1",echoPort,active.Port,lifetime.Token))
{
    await game.ConnectAsync(IPAddress.Loopback,echoPort,lifetime.Token);await Ping(game,"game before");await Ping(flow,"YouTube-like before");
    for(int n=0;n<3;n++){using var scout=await EngineProcess.StartAsync(native,bank[n+2],job,lifetime.Token);await Ping(game,"game during");await Ping(flow,"active during");}
    active.Retire();Assert(active.Alive,"Retired engine killed a live stream");await Ping(flow,"after retirement");await Ping(game,"game after");
}
echo.Stop();Console.WriteLine("Unrelated persistent TCP flow and active proxied stream survived three scout engine cycles and retirement");

int routed=0;
await using(var gateway=new LocalGateway(0,0,new string('a',40),(host,port,token)=>{Interlocked.Increment(ref routed);throw new IOException("Test target deliberately not dialed");},_=>{}))
{
    using var http=new HttpClient(new HttpClientHandler{UseProxy=false});string pac=await http.GetStringAsync(gateway.PacUrl,lifetime.Token);
    Assert(pac.Contains("SOCKS5")&&pac.Contains("DIRECT"),"PAC not served");
    string? pacOutput=Environment.GetEnvironmentVariable("PAC_TEST_OUTPUT");if(pacOutput is not null)File.WriteAllText(pacOutput,pac);
    foreach(string host in new[]{"example.com","steamcommunity.com","youtube.com.evil.test","127.0.0.1"})
    {try{using var ignored=await Socks.ConnectAsync(host,443,gateway.ProxyPort,lifetime.Token);throw new Exception("Unrelated hostname accepted by broker");}catch(IOException){}}
    Assert(routed==0,"Rejected domains reached upstream connector");
    string? chrome=new[]{@"C:\Program Files\Google\Chrome\Application\chrome.exe",@"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"}.FirstOrDefault(File.Exists);
    Assert(chrome is not null,"Actual Google Chrome required for routing test");
    async Task<string> Chrome(string url,string extra="")
    {
        string directory=Path.Combine(Path.GetTempPath(),"DpiPilot-Chrome-test-"+Guid.NewGuid());
        var info=new ProcessStartInfo(chrome!){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
        foreach(string flag in new[]{"--headless=new","--disable-gpu","--no-first-run","--no-default-browser-check","--disable-background-networking","--disable-component-update","--disable-sync","--disable-features=HttpsUpgrades,HttpsFirstBalancedModeAutoEnable","--user-data-dir="+directory,"--proxy-pac-url="+gateway.PacUrl,"--dump-dom"})info.ArgumentList.Add(flag);
        if(extra!="")info.ArgumentList.Add(extra);info.ArgumentList.Add(url);
        using var process=Process.Start(info)!;var output=process.StandardOutput.ReadToEndAsync();_=process.StandardError.ReadToEndAsync();
        try{await process.WaitForExitAsync(new CancellationTokenSource(TimeSpan.FromSeconds(35)).Token);return await output;}
        finally{if(!process.HasExited)process.Kill(true);try{Directory.Delete(directory,true);}catch(IOException){}}
    }
    // Browser-side DNS maps an unrelated site to a local test endpoint; it must never enter the proxy.
    var direct=new TcpListener(IPAddress.Loopback,0);direct.Start();int directPort=((IPEndPoint)direct.LocalEndpoint).Port;
    var directTask=Task.Run(async()=>{using var client=await direct.AcceptTcpClientAsync(lifetime.Token);var stream=client.GetStream();byte[] request=new byte[4096];int received=await stream.ReadAsync(request,lifetime.Token);Assert(received>0,"Empty browser request");byte[] response=Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Length: 18\r\nConnection: close\r\n\r\nDIRECT-GAME-MARKER");await stream.WriteAsync(response,lifetime.Token);});
    string directHtml=await Chrome($"http://isolated.direct.test:{directPort}/","--host-resolver-rules=MAP isolated.direct.test 127.0.0.1");
    Assert(directHtml.Contains("DIRECT-GAME-MARKER"),"Chrome unrelated URL did not remain DIRECT");Assert(routed==0,"Chrome proxied unrelated URL");await directTask;direct.Stop();
    _=await Chrome("https://www.youtube.com");Assert(routed>0,"Chrome YouTube URL did not use selective proxy");
}
Console.WriteLine("Actual Google Chrome: YouTube entered loopback broker; unrelated site stayed DIRECT; broker rejected non-YouTube hosts");
if(OperatingSystem.IsWindows())
{using var internet=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings");Console.WriteLine("Read-only registry snapshot available: "+(internet is not null));}
Assert(registryBefore==RegistrySnapshot(),"System WinINet/WinHTTP proxy configuration changed");
Console.WriteLine("System WinINet/WinHTTP proxy configuration unchanged");
Console.WriteLine("ALL WINDOWS CORE / ROUTING TESTS PASSED");

// A verified profile never performs timer-driven network probes in the default mode.
var scheduleNow=DateTimeOffset.UtcNow;
if(new Settings().PeriodicChecks || CheckPolicy.ShouldProbe(false,false,false,true,scheduleNow,DateTimeOffset.MinValue)) throw new Exception("Quiet default regressed");
if(!CheckPolicy.ShouldProbe(false,true,false,true,scheduleNow,DateTimeOffset.MaxValue) || !CheckPolicy.ShouldProbe(false,false,false,false,scheduleNow,DateTimeOffset.MaxValue)) throw new Exception("Explicit/start recovery check lost");
if(CheckPolicy.Next(false,scheduleNow)!=DateTimeOffset.MaxValue || CheckPolicy.Candidates(false,false)!=3 || CheckPolicy.Candidates(false,true)!=12) throw new Exception("Quiet scan budget regressed");
Console.WriteLine("Quiet startup/event-only probe policy verified");
