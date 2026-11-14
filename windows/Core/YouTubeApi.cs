using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace DpiPilot;

public sealed record ApiAnswer(bool Ok,bool Unavailable,string Detail);
/// Player JSON demonstrates API transport only. UNPLAYABLE is never media proof.
public static class YouTubeApi
{
    public static string Body(string video="jNQXAC9IVRw",string version="2.20240709.01.00") => JsonSerializer.Serialize(new{videoId=video,context=new{client=new{clientName="WEB",clientVersion=version,hl="en"}}});
    public static ApiAnswer Assess(int status,string type,byte[] body)
    {
        if(status!=200)return new(false,true,$"API HTTP {status}: источник проверки недоступен");
        try {
            if(!type.Split(';')[0].Trim().Equals("application/json",StringComparison.OrdinalIgnoreCase))return new(false,true,"API: неверный тип ответа");
            using var doc=JsonDocument.Parse(body);var root=doc.RootElement;
            if(root.ValueKind!=JsonValueKind.Object || !root.TryGetProperty("playabilityStatus",out var play) || play.ValueKind!=JsonValueKind.Object || !play.TryGetProperty("status",out var state) || state.ValueKind!=JsonValueKind.String || !Regex.IsMatch(state.GetString()!,"^[A-Z_]{1,40}$"))return new(false,true,"API: player JSON не получен");
            var reasons=new List<string>();
            if(play.TryGetProperty("reason",out var reason) && reason.ValueKind==JsonValueKind.String)reasons.Add(reason.GetString()!);
            if(play.TryGetProperty("errorScreen",out var screen) && screen.ValueKind==JsonValueKind.Object && screen.TryGetProperty("playerErrorMessageRenderer",out var renderer) && renderer.ValueKind==JsonValueKind.Object)
                foreach(string key in new[]{"reason","subreason"})if(renderer.TryGetProperty(key,out var text) && text.ValueKind==JsonValueKind.Object) {
                    if(text.TryGetProperty("simpleText",out var simple) && simple.ValueKind==JsonValueKind.String)reasons.Add(simple.GetString()!);
                    else if(text.TryGetProperty("runs",out var runs) && runs.ValueKind==JsonValueKind.Array)reasons.Add(string.Concat(runs.EnumerateArray().Where(r=>r.ValueKind==JsonValueKind.Object && r.TryGetProperty("text",out var t) && t.ValueKind==JsonValueKind.String).Select(r=>r.GetProperty("text").GetString())));
                }
            string detail=Regex.Replace(string.Join("; ",reasons.Distinct()),@"https?://\S+","[URL скрыт]",RegexOptions.IgnoreCase);
            detail=new string(detail.Select(c=>char.IsControl(c) ? ' ' : c).Take(240).ToArray());
            return new(true,false,$"API JSON: {state.GetString()}; {detail} (транспорт, не видео)");
        } catch(JsonException) {return new(false,true,"API: некорректный player JSON");}
    }
}
