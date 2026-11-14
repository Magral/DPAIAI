using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace DpiPilot;

public enum PlaybackState { Confirmed,Failed,Unavailable }
public sealed record PlaybackSample(PlaybackState State,int Bytes,long ElapsedMs,long Kbps,string Detail);
public sealed record PlaybackRequest(string Host,string Path="/",int? ProxyPort=null,string? Body=null,int Limit=4096,bool Range=false);
public sealed record PlaybackTrack(string Kind,string Url);
public sealed record PlaybackResponse(int Status,string ContentType,byte[] Body);

/// Manual diagnostic only. No profile, strategy, DNS, PAC or registry mutations.
public sealed class PlaybackDiagnostic(
    Func<PlaybackRequest,CancellationToken,Task<PlaybackResponse>>? transport=null,
    Func<string,CancellationToken,Task<string[]>>? resolve=null,Action? ensureRoute=null)
{
    private readonly Func<PlaybackRequest,CancellationToken,Task<PlaybackResponse>> exchange=transport ?? ExchangeAsync;
    private readonly Func<string,CancellationToken,Task<string[]>> lookup=resolve ?? ResolveAsync;
    public static bool MediaUri(string value) => value.Length<=8192 && !value.Any(char.IsControl) && Uri.TryCreate(value,UriKind.Absolute,out var u) &&
        u.Scheme=="https" && u.UserInfo.Length==0 && u.Fragment.Length==0 && u.Port==443 &&
        u.Host.EndsWith(".googlevideo.com",StringComparison.Ordinal) && u.AbsolutePath=="/videoplayback";
    public static PlaybackSample Assess(int status,string type,byte[] body,long elapsed)
    {
        long ms=Math.Max(1,elapsed),kbps=body.Length*8L/ms;
        PlaybackSample Result(PlaybackState state,string detail)=>new(state,body.Length,ms,kbps,detail);
        if(status is 403 or 404 or 410 or 429)return Result(PlaybackState.Unavailable,"Тестовый адрес отклонён/истёк; стратегии не исключаются");
        if(status is not (200 or 206))return Result(PlaybackState.Failed,"Неверный HTTP медиасервера");
        if(!(type.StartsWith("video/",StringComparison.OrdinalIgnoreCase)||type.StartsWith("audio/",StringComparison.OrdinalIgnoreCase)))return Result(PlaybackState.Failed,"Вместо медиа получен другой тип содержимого");
        if(body.Length<32768)return Result(PlaybackState.Failed,"Получено меньше32КБ");
        bool mp4=Encoding.ASCII.GetString(body,4,4) is "ftyp" or "styp";
        bool webm=body.AsSpan(0,4).SequenceEqual(new byte[]{0x1a,0x45,0xdf,0xa3});
        if(!mp4 && !webm)return Result(PlaybackState.Failed,"Не распознан MP4/WebM");
        if(kbps<512)return Result(PlaybackState.Failed,"Образец медленнее512Кбит/с; возможна буферизация");
        return Result(PlaybackState.Confirmed,"Медиадиапазон получен; это не гарантия других роликов и воспроизведения в клиенте");
    }
    public static PlaybackState Overall(IReadOnlyList<PlaybackSample> samples)=>samples.Any(s=>s.State==PlaybackState.Failed) ? PlaybackState.Failed : samples.Count>0 && samples.All(s=>s.State==PlaybackState.Confirmed) ? PlaybackState.Confirmed : PlaybackState.Unavailable;
    public static JsonElement? Player(string html)
    {
        foreach(string marker in new[]{"ytInitialPlayerResponse = ","ytInitialPlayerResponse="}) {
            int from=html.IndexOf(marker,StringComparison.Ordinal);if(from<0)continue;
            int start=html.IndexOf('{',from+marker.Length);if(start<0)continue;
            int depth=0;bool quoted=false,escape=false;
            for(int i=start;i<html.Length;i++) {
                char c=html[i];
                if(quoted) {if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}
                else if(c=='"')quoted=true;else if(c=='{')depth++;else if(c=='}' && --depth==0)return Json(html[start..(i+1)]);
            }
        }return null;
    }
    private static JsonElement? Json(string text) {try {using var doc=JsonDocument.Parse(text);return doc.RootElement.Clone();}catch(JsonException){return null;}}
    public static IReadOnlyList<string> Urls(JsonElement? player)
    {
        var result=new List<string>();
        if(player is not {} root || root.ValueKind!=JsonValueKind.Object || !root.TryGetProperty("streamingData",out var data) || data.ValueKind!=JsonValueKind.Object)return result;
        foreach(string name in new[]{"formats","adaptiveFormats"})if(data.TryGetProperty(name,out var formats) && formats.ValueKind==JsonValueKind.Array)
            foreach(var f in formats.EnumerateArray())if(f.ValueKind==JsonValueKind.Object && f.TryGetProperty("mimeType",out var mime) && mime.ValueKind==JsonValueKind.String &&
                (mime.GetString()!.StartsWith("video/",StringComparison.Ordinal)||mime.GetString()!.StartsWith("audio/",StringComparison.Ordinal)) &&
                f.TryGetProperty("url",out var url) && url.ValueKind==JsonValueKind.String && MediaUri(url.GetString()!))result.Add(url.GetString()!);
        return result.Distinct().ToArray();
    }
    public static IReadOnlyList<PlaybackTrack> Samples(JsonElement? player)
    {
        var selected=new Dictionary<string,PlaybackTrack>();
        if(player is not {} root || root.ValueKind!=JsonValueKind.Object || !root.TryGetProperty("streamingData",out var data) || data.ValueKind!=JsonValueKind.Object)return [];
        foreach(string name in new[]{"formats","adaptiveFormats"})if(data.TryGetProperty(name,out var formats) && formats.ValueKind==JsonValueKind.Array)
            foreach(var f in formats.EnumerateArray())if(f.ValueKind==JsonValueKind.Object && f.TryGetProperty("mimeType",out var mime) && mime.ValueKind==JsonValueKind.String && f.TryGetProperty("url",out var url) && url.ValueKind==JsonValueKind.String && MediaUri(url.GetString()!)) {
                string type=mime.GetString()!,kind=type.StartsWith("video/",StringComparison.Ordinal) ? "video" : type.StartsWith("audio/",StringComparison.Ordinal) ? "audio" : "";
                if(kind.Length>0 && !selected.ContainsKey(kind))selected[kind]=new(kind,url.GetString()!);
            }
        return new[]{"video","audio"}.Where(selected.ContainsKey).Select(k=>selected[k]).ToArray();
    }
    public async Task<string> RunAsync(int? proxyPort,CancellationToken cancellation,Action<string>? progress=null)
    {
        using var route=ensureRoute is null ? new PlaybackRouteGuard() : null;
        void Check() {cancellation.ThrowIfCancellationRequested();if(ensureRoute is not null)ensureRoute();else route!.Check();}
        var lines=new List<string>{"YouTube: диагностика меню и медиадоставки v1",proxyPort is {} p ? $"Маршрут: локальный SOCKS5 127.0.0.1:{p}" : "Маршрут: Windows без локального обхода (внешний VPN должен включать это приложение)","Узлы6/6 и TLS не доказывают видео; настройки и стратегии не меняются"};
        void Add(string value) {Check();lines.Add(value);progress?.Invoke(value);}
        async Task<PlaybackResponse?> Request(string label,PlaybackRequest req) {
            Check();Add(label+": начало");var timer=Stopwatch.StartNew();
            try {var response=await exchange(req,cancellation);Add($"{label}: HTTP{response.Status}; {response.Body.Length} байт; {timer.ElapsedMilliseconds} мс");return response;}
            catch(OperationCanceledException) when(cancellation.IsCancellationRequested) {throw;}
            catch(Exception e) {cancellation.ThrowIfCancellationRequested();Add(label+": ОШИБКА "+e.GetType().Name+(e is HttpRequestException h ? "/"+h.HttpRequestError : "")+" (URL и токены скрыты)");return null;}
        }
        foreach(string host in new[]{"youtubei.googleapis.com","redirector.googlevideo.com"}) {
            cancellation.ThrowIfCancellationRequested();
            try {Add("DNS устройства "+host+": "+string.Join(", ",await lookup(host,cancellation)));}
            catch(OperationCanceledException) when(cancellation.IsCancellationRequested){throw;}
            catch(Exception e){cancellation.ThrowIfCancellationRequested();Add("DNS устройства "+host+": "+e.GetType().Name);}
            var dns=await Request("Публичный DNS (без локального обхода)",new("dns.google","/resolve?name="+host+"&type=A",Limit:16384));
            var json=dns is null ? null : Json(Encoding.UTF8.GetString(dns.Body));
            if(json is {} j && j.ValueKind==JsonValueKind.Object && j.TryGetProperty("Answer",out var answers) && answers.ValueKind==JsonValueKind.Array) {
                var values=answers.EnumerateArray().Where(a=>a.ValueKind==JsonValueKind.Object && a.TryGetProperty("type",out var t) && t.ValueKind==JsonValueKind.Number && t.TryGetInt32(out var kind) && kind==1 && a.TryGetProperty("data",out var d) && d.ValueKind==JsonValueKind.String)
                    .Select(a=>a.GetProperty("data").GetString()!).Where(s=>Regex.IsMatch(s,"^[0-9.]{7,15}$"));Add("DNS Google "+host+": "+string.Join(", ",values));
            }
        }
        await Request("Контроль интернета (без локального обхода)",new("www.gstatic.com","/generate_204",Limit:1));
        var mapping=await Request("Карта CDN",new("redirector.googlevideo.com","/report_mapping?di=no",proxyPort));
        if(mapping is not null) {var parts=Encoding.UTF8.GetString(mapping.Body).Split("=>");if(parts.Length==2 && Regex.IsMatch(parts[1].Trim(),"^[a-zA-Z0-9 _.,()=-]{1,160}$"))Add("CDN mapping: "+parts[1].Trim());}
        var urls=new List<PlaybackTrack>();int apiResponses=0;
        foreach(string video in new[]{"jNQXAC9IVRw","M7lc1UVf-VE"}) {
            var page=await Request("Публичная страница "+video,new("www.youtube.com","/watch?v="+video+"&hl=en",proxyPort,Limit:2*1024*1024));
            string html=page is null ? "" : Encoding.UTF8.GetString(page.Body);var embedded=Player(html);
            var version=Regex.Match(html,"\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,64})\"");
            string body=JsonSerializer.Serialize(new{videoId=video,context=new{client=new{clientName="WEB",clientVersion=version.Success ? version.Groups[1].Value : "2.20240709.01.00",hl="en"}}});
            var api=await Request("API player "+video,new("youtubei.googleapis.com","/youtubei/v1/player?prettyPrint=false",proxyPort,body,1024*1024));
            var player=api is null ? null : Json(Encoding.UTF8.GetString(api.Body));string state="";
            if(player is {} j && j.ValueKind==JsonValueKind.Object && j.TryGetProperty("playabilityStatus",out var ps) && ps.ValueKind==JsonValueKind.Object && ps.TryGetProperty("status",out var status) && status.ValueKind==JsonValueKind.String)state=status.GetString()!;
            if(api?.Status==200 && Regex.IsMatch(state,"^[A-Z_]{1,40}$")){apiResponses++;Add("API JSON получен; playabilityStatus="+state+" (это не подтверждение видео)");}
            else Add("API: содержательный ответ player не подтверждён");
            urls.AddRange(Samples(embedded).Concat(Samples(player)).DistinctBy(t=>t.Kind));
        }
        var samples=new List<(string Kind,PlaybackSample Sample)>();
        foreach(var track in urls.DistinctBy(t=>t.Url).Take(4)) {
            Check();var u=new Uri(track.Url);var timer=Stopwatch.StartNew();
            var response=await Request("Медиадиапазон "+u.Host,new(u.Host,u.PathAndQuery,proxyPort,Limit:256*1024,Range:true));
            var sample=response is null ? new PlaybackSample(PlaybackState.Failed,0,timer.ElapsedMilliseconds,0,"Медиазапрос не завершён; причина выше") : Assess(response.Status,response.ContentType,response.Body,timer.ElapsedMilliseconds);
            samples.Add((track.Kind,sample));Add($"{track.Kind} {u.Host}: {sample.State}; {sample.Bytes} байт / {sample.ElapsedMs} мс; {sample.Kbps} Кбит/с; {sample.Detail}");
        }
        Add($"API: {apiResponses}/2 содержательных ответов публичных образцов");
        var videoState=Overall(samples.Where(s=>s.Kind=="video").Select(s=>s.Sample).ToArray());
        var audioState=Overall(samples.Where(s=>s.Kind=="audio").Select(s=>s.Sample).ToArray());
        Add($"ВИДЕО: {videoState}; АУДИО: {audioState} (Unavailable=не проверено)");
        var overall=videoState==PlaybackState.Confirmed && audioState==PlaybackState.Confirmed ? PlaybackState.Confirmed : videoState==PlaybackState.Failed || audioState==PlaybackState.Failed ? PlaybackState.Failed : PlaybackState.Unavailable;
        Add(overall switch {PlaybackState.Confirmed=>"МЕДИА: ОБРАЗЕЦ ЗАГРУЖЕН; воспроизведение других роликов/Shorts проверяется отдельно",PlaybackState.Failed=>"МЕДИА: ЕСТЬ СБОЙ ИЛИ НИЗКАЯ СКОРОСТЬ",_=>"МЕДИА: НЕ ПРОВЕРЕНО; вход/consent/подпись/SABR/недоступный образец; стратегии не исключались"});
        Add("Сравните обычную сеть и платный VPN. Различие DNS само по себе не доказывает подмену: CDN штатно выдаёт разные адреса.");
        return string.Join(Environment.NewLine,lines);
    }
    private static async Task<string[]> ResolveAsync(string host,CancellationToken cancellation)
    {
        using var limit=CancellationTokenSource.CreateLinkedTokenSource(cancellation);limit.CancelAfter(TimeSpan.FromSeconds(6));
        return (await Dns.GetHostAddressesAsync(host,limit.Token)).Select(a=>a.ToString()).ToArray();
    }
    private static async Task<PlaybackResponse> ExchangeAsync(PlaybackRequest req,CancellationToken cancellation)
    {
        if(!(req.Host is "www.youtube.com" or "youtubei.googleapis.com" or "redirector.googlevideo.com" or "www.gstatic.com" or "dns.google" || Regex.IsMatch(req.Host,"^[a-zA-Z0-9.-]+\\.googlevideo\\.com$")) ||
            !req.Path.StartsWith('/') || req.Path.Any(char.IsControl) || req.Path.Length>8192 || req.Limit is <1 or >2097152 || (req.ProxyPort is {} port && (port<1024 || port>65535)))throw new ArgumentException("Invalid diagnostic destination");
        using var limit=CancellationTokenSource.CreateLinkedTokenSource(cancellation);limit.CancelAfter(TimeSpan.FromSeconds(18));
        using var handler=new SocketsHttpHandler {UseProxy=false,AllowAutoRedirect=false,ConnectTimeout=TimeSpan.FromSeconds(6),MaxResponseHeadersLength=32,
            ConnectCallback=async(context,token)=> {
                if(req.ProxyPort is {} p)return (await Socks.ConnectAsync(context.DnsEndPoint.Host,443,p,token)).GetStream();
                var socket=new TcpClient{NoDelay=true};try {await socket.ConnectAsync(context.DnsEndPoint.Host,443,token);return socket.GetStream();}catch {socket.Dispose();throw;}
            }};
        using var http=new HttpClient(handler);using var message=new HttpRequestMessage(req.Body is null ? HttpMethod.Get : HttpMethod.Post,new Uri("https://"+req.Host+req.Path)) {Version=HttpVersion.Version11,VersionPolicy=HttpVersionPolicy.RequestVersionExact};
        message.Headers.ConnectionClose=true;message.Headers.UserAgent.ParseAdd("Mozilla/5.0");message.Headers.AcceptEncoding.ParseAdd("identity");
        if(req.Range)message.Headers.Range=new(0,req.Limit-1);if(req.Body is not null)message.Content=new StringContent(req.Body,Encoding.UTF8,"application/json");
        using var response=await http.SendAsync(message,HttpCompletionOption.ResponseHeadersRead,limit.Token);await using var stream=await response.Content.ReadAsStreamAsync(limit.Token);
        using var output=new MemoryStream();byte[] buffer=new byte[8192];
        while(output.Length<req.Limit){int n=await stream.ReadAsync(buffer.AsMemory(0,(int)Math.Min(buffer.Length,req.Limit-output.Length)),limit.Token);if(n==0)break;output.Write(buffer,0,n);}
        return new((int)response.StatusCode,response.Content.Headers.ContentType?.MediaType ?? "",output.ToArray());
    }
}
