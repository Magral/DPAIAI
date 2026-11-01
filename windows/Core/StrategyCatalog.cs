using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace DpiPilot;

public sealed record Strategy(string Id, string Args, string Origin);
public sealed record CatalogData(long Sequence, string Version, IReadOnlyList<Strategy> Entries);

public static partial class CatalogProtocol
{
    public const string Engine = "ba532298de7b28cfe854aea83d061369d13ca290";
    public const string CertificatePin = "c445f31d1345b3766a1f269f86f8953997938196eeade34462b61cc7024a8a56";
    public const string Prefix = "DPI-PILOT-TV-CATALOG-v1\n";
    private static readonly HashSet<string> Flags = ["md5sig", "drop-sack", "tfo", "wait-send"];
    private static readonly HashSet<string> Options = ["md5sig", "drop-sack", "tfo", "wait-send", "split", "disorder", "oob", "disoob", "fake", "ttl", "fake-offset", "fake-sni", "fake-tls-mod", "oob-data", "mod-http", "tlsrec", "tlsminor", "udp-fake", "timeout", "auto", "auto-mode", "cache-ttl", "proto", "pf", "round", "buf-size", "await-int"];
    [GeneratedRegex("^[A-Za-z0-9_.,:+?*#=\\-]+$")]
    private static partial Regex ArgumentPattern();
    [GeneratedRegex("[0-9]+")]
    private static partial Regex Numbers();
    public static string Id(string args) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(args)))[..16].ToLowerInvariant();
    public static bool ValidCommand(string args)
    {
        if (args.Length > 4096) return false;
        if (args == "") return true;
        var tokens = args.Split(' '); if (tokens.Length > 480 || tokens.Any(t => t.Length == 0)) return false;
        int groups = 0;
        for (int i = 0; i < tokens.Length;)
        {
            if (!tokens[i].StartsWith("--", StringComparison.Ordinal)) return false;
            var name = tokens[i++][2..]; if (!Options.Contains(name)) return false;
            if (Flags.Contains(name)) continue;
            if (i >= tokens.Length) return false;
            var value = tokens[i++];
            if (value.Length > 253 || !ArgumentPattern().IsMatch(value)) return false;
            if (Numbers().Matches(value).Any(m => !long.TryParse(m.Value, out long n) || n > 86400)) return false;
            (int Low, int High)? range = name switch
            {
                "buf-size" => (500,65536), "timeout" => (1,60), "ttl" => (1,255), "udp-fake" => (0,16),
                "cache-ttl" => (0,86400), "await-int" => (0,1000), _ => null
            };
            if (range is { } r && (!int.TryParse(value, out int number) || number < r.Low || number > r.High)) return false;
            if (name == "auto" && ++groups > 16) return false;
            if (name == "round" && value.Split(':').Any(v => !int.TryParse(v, out int n) || n is < 0 or > 40)) return false;
            if (name == "fake-sni" && !Regex.IsMatch(value, "^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")) return false;
        }
        return true;
    }
    public static bool WindowsCompatible(Strategy s) => !s.Args.Split(' ').Any(t => t is "--md5sig" or "--drop-sack" or "--tfo");
    public static CatalogData Verify(byte[] envelope, RSA publicKey, long minimum)
    {
        try { return Parse(envelope,publicKey,minimum); }
        catch (Exception e) when (e is JsonException or ArgumentException or InvalidOperationException or KeyNotFoundException or NullReferenceException or OverflowException)
        { throw new InvalidDataException("Malformed catalog",e); }
    }
    private static CatalogData Parse(byte[] envelope,RSA publicKey,long minimum)
    {
        if (envelope.Length > 3*1024*1024) throw new InvalidDataException("Catalog too large");
        using var wrapper = JsonDocument.Parse(envelope);
        byte[] data = Convert.FromBase64String(wrapper.RootElement.GetProperty("payload").GetString()!);
        byte[] signature = Convert.FromBase64String(wrapper.RootElement.GetProperty("signature").GetString()!);
        if (data.Length is 0 or > 2*1024*1024 || signature.Length is < 256 or > 1024) throw new InvalidDataException("Catalog size");
        byte[] signedData = [.. Encoding.UTF8.GetBytes(Prefix), .. data];
        if (!publicKey.VerifyData(signedData, signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1)) throw new CryptographicException("Catalog signature");
        using var document = JsonDocument.Parse(data); var root = document.RootElement;
        if (root.GetProperty("schema").GetInt32() != 1 || root.GetProperty("engine_commit").GetString() != Engine) throw new InvalidDataException("Catalog engine/schema");
        long sequence = root.GetProperty("sequence").GetInt64(); if (sequence <= 0 || sequence < minimum) throw new InvalidDataException("Catalog rollback");
        string version = root.GetProperty("version").GetString()!;
        if (!Regex.IsMatch(version, "^[A-Za-z0-9._-]{1,64}$")) throw new InvalidDataException("Catalog version");
        var array = root.GetProperty("strategies"); if (array.GetArrayLength() is < 1 or > 2000) throw new InvalidDataException("Catalog count");
        var entries = new List<Strategy>(); var ids = new HashSet<string>();
        foreach (var item in array.EnumerateArray())
        {
            string args = item.GetProperty("args").GetString()!, id = item.GetProperty("id").GetString()!;
            var sources = item.GetProperty("sources").EnumerateArray().Select(s => s.GetString()!).ToArray();
            if (sources.Length > 32 || sources.Any(s => !Regex.IsMatch(s, "^[a-zA-Z0-9_-]{1,64}$"))) throw new InvalidDataException("Catalog origin");
            if (!ValidCommand(args) || Id(args) != id || !ids.Add(id)) throw new InvalidDataException("Catalog command");
            entries.Add(new(id, args, string.Join(", ", sources)));
        }
        return new(sequence, version, entries);
    }
}

public sealed class CatalogStore
{
    private readonly SettingsStore settings;
    private readonly RSA key;
    private readonly SemaphoreSlim gate = new(1);
    private CatalogData snapshot;
    public CatalogData Snapshot => Volatile.Read(ref snapshot);
    public string Status { get; private set; } = "Встроенная подписанная база";
    public CatalogStore(SettingsStore settings, string certificatePem, byte[] embedded)
    {
        this.settings = settings;
        using var certificate = X509Certificate2.CreateFromPem(certificatePem);
        if (Convert.ToHexString(certificate.GetCertHash(HashAlgorithmName.SHA256)).ToLowerInvariant() != CatalogProtocol.CertificatePin) throw new CryptographicException("Permanent certificate mismatch");
        key = certificate.GetRSAPublicKey() ?? throw new CryptographicException("RSA certificate required");
        snapshot = Filter(CatalogProtocol.Verify(embedded, key, 0));
        if(settings.Value.CatalogSequence < snapshot.Sequence) settings.Change(s => {s.CatalogSequence=snapshot.Sequence;s.CatalogDigest=Digest(embedded);s.CatalogChecked=default;});
        string path = Path.Combine(settings.DirectoryPath,"catalog-v1.json");
        if (File.Exists(path)) try
        {
            byte[] cached = File.ReadAllBytes(path);
            var update = Filter(CatalogProtocol.Verify(cached, key, settings.Value.CatalogSequence));
            CheckDigest(cached, update.Sequence);
            snapshot = update;
            settings.Change(s => { s.CatalogSequence = update.Sequence; s.CatalogDigest = Digest(cached); });
            Status = "Загружена сохранённая подписанная база";
        }
        catch (Exception e) when (e is IOException or JsonException or CryptographicException or FormatException or KeyNotFoundException)
        { Status = "Кеш не прошёл проверку; используется встроенная база"; settings.Change(s => { s.CatalogChecked = default; s.CatalogAttempt = default; }); }
    }
    private static CatalogData Filter(CatalogData data)
    {
        var entries = data.Entries.Where(CatalogProtocol.WindowsCompatible).ToArray();
        if (entries.Length < 8) throw new InvalidDataException("Too few Windows strategies");
        return data with { Entries = entries };
    }
    private static string Digest(byte[] data) => Convert.ToHexString(SHA256.HashData(data));
    private void CheckDigest(byte[] bytes, long sequence)
    {
        if (sequence == settings.Value.CatalogSequence && settings.Value.CatalogDigest != "" && settings.Value.CatalogDigest != Digest(bytes)) throw new InvalidDataException("Catalog sequence collision");
    }
    public async Task RefreshAsync(bool force, CancellationToken cancellation)
    {
        if (!await gate.WaitAsync(0, cancellation)) return;
        var externalCancellation = cancellation;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation); deadline.CancelAfter(TimeSpan.FromSeconds(90)); cancellation = deadline.Token;
        try
        {
            var now = DateTimeOffset.UtcNow;
            if (!force && now >= settings.Value.CatalogChecked && now-settings.Value.CatalogChecked < TimeSpan.FromDays(1)) return;
            if (now >= settings.Value.CatalogAttempt && now-settings.Value.CatalogAttempt < TimeSpan.FromSeconds(force ? 15 : 3600)) return;
            settings.Change(s => s.CatalogAttempt = now); Status = "Проверка подписанной базы…";
            using var handler = new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false };
            using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(25) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("DPI-Pilot-Windows/0.1.1");
            var url = new Uri("https://github.com/Magral/DPAIAI/releases/download/strategy-catalog/catalog-v1.json");
            for (int attempt = 0; attempt < 4; attempt++)
            {
                if (url.Scheme != "https" || url.Port != 443 || url.UserInfo != "" || !new[] { "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com" }.Contains(url.Host)) throw new InvalidDataException("Catalog redirect");
                using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellation);
                if ((int)response.StatusCode is 301 or 302 or 303 or 307 or 308)
                { url = new Uri(url, response.Headers.Location ?? throw new InvalidDataException("Missing redirect")); continue; }
                response.EnsureSuccessStatusCode();
                if (response.Content.Headers.ContentLength > 3*1024*1024) throw new InvalidDataException("Catalog size");
                using var input = await response.Content.ReadAsStreamAsync(cancellation); using var output = new MemoryStream(); byte[] buffer = new byte[8192];
                while (true) { int read = await input.ReadAsync(buffer, cancellation); if (read == 0) break; if (output.Length+read > 3*1024*1024) throw new InvalidDataException("Catalog size"); output.Write(buffer,0,read); }
                byte[] bytes = output.ToArray(); var update = Filter(CatalogProtocol.Verify(bytes,key,settings.Value.CatalogSequence)); CheckDigest(bytes,update.Sequence);
                SettingsStore.AtomicWrite(Path.Combine(settings.DirectoryPath,"catalog-v1.json"),bytes);
                settings.Change(s => { s.CatalogSequence = update.Sequence; s.CatalogDigest = Digest(bytes); s.CatalogChecked = now; });
                Volatile.Write(ref snapshot,update); Status = "Подпись проверена; следующая проверка через сутки"; return;
            }
            throw new InvalidDataException("Too many redirects");
        }
        catch (OperationCanceledException) when (externalCancellation.IsCancellationRequested) { throw; }
        catch (Exception e) when (e is HttpRequestException or IOException or JsonException or CryptographicException or FormatException or KeyNotFoundException or OperationCanceledException)
        { Status = "Обновление недоступно ("+e.GetType().Name+"); прежняя база сохранена, повтор через час"; }
        finally { gate.Release(); }
    }
}
