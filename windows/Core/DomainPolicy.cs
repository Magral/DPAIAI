using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace DpiPilot;

/// Single source for both PAC and the loopback broker. Shared Google domains stay DIRECT.
public static partial class DomainPolicy
{
    public static readonly string[] Trees = ["youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com"];
    public static readonly string[] Exact = ["youtubei.googleapis.com", "youtube.googleapis.com", "yt3.ggpht.com", "yt4.ggpht.com", "yt3.googleusercontent.com"];
    [GeneratedRegex("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")]
    private static partial Regex HostPattern();
    public static string Normalize(string host) => host.TrimEnd('.').ToLowerInvariant();
    public static bool Allows(string host, int port = 443)
    {
        host = Normalize(host);
        return port == 443 && HostPattern().IsMatch(host) && !IPAddress.TryParse(host, out _) &&
            (Exact.Contains(host) || Trees.Any(d => host == d || host.EndsWith("." + d, StringComparison.Ordinal)));
    }
    public static string Pac(int port)
    {
        if (port is < 1 or > 65535) throw new ArgumentOutOfRangeException(nameof(port));
        return "function FindProxyForURL(url, host) {\n" +
            "host=host.toLowerCase().replace(/\\.$/,'');\n" +
            "if (url.substring(0,6) !== 'https:' && url.substring(0,4) !== 'wss:') return 'DIRECT';\n" +
            "var trees=" + JsonSerializer.Serialize(Trees) + "; var exact=" + JsonSerializer.Serialize(Exact) + ";\n" +
            "for(var j=0;j<exact.length;j++) if(host===exact[j]) return 'SOCKS5 127.0.0.1:" + port + "';\n" +
            "for(var i=0;i<trees.length;i++) { var d=trees[i]; if(host===d || host.slice(-(d.length+1))==='.'+d) return 'SOCKS5 127.0.0.1:" + port + "'; }\n" +
            "return 'DIRECT';\n}\n";
    }
}
