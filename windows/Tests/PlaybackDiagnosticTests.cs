using DpiPilot;

internal static class PlaybackDiagnosticTests
{
    public static async Task RunAsync()
    {
        void Check(bool value,string reason) {if(!value)throw new Exception(reason);}
        var api=YouTubeApi.Assess(200,"application/json","{\"playabilityStatus\":{\"status\":\"UNPLAYABLE\",\"reason\":\"Valid client required https://example.test/?token=PRIVATE\"}}"u8.ToArray());
        Check(api.Ok && !api.Unavailable && api.Detail.Contains("UNPLAYABLE") && api.Detail.Contains("Valid client") && !api.Detail.Contains("PRIVATE"),"Player transport/reason handling incorrect");
        Check(!YouTubeApi.Assess(200,"text/html","{}"u8.ToArray()).Ok,"HTML became API proof");
        Check(!YouTubeApi.Assess(200,"application/json","{}"u8.ToArray()).Ok,"Unrelated JSON became API proof");
        foreach(int status in new[]{400,401,403,404,410,429,503})Check(YouTubeApi.Assess(status,"text/html",[]).Unavailable,"Unavailable API source condemned route");
        Check(PlaybackDiagnostic.Overall([])==PlaybackState.Unavailable,"TLS-only proof became media success");
        var bytes=new byte[65536];"ftyp"u8.CopyTo(bytes.AsSpan(4));
        Check(PlaybackDiagnostic.Assess(206,"video/mp4",bytes,250).State==PlaybackState.Confirmed,"Real media range rejected");
        Check(PlaybackDiagnostic.Assess(206,"video/mp4",bytes,10000).State==PlaybackState.Failed,"Slow range qualified");
        Check(PlaybackDiagnostic.Assess(200,"text/html",bytes,250).State==PlaybackState.Failed,"HTML qualified");
        foreach(int status in new[]{403,404,410,429})Check(PlaybackDiagnostic.Assess(status,"text/html",[],100).State==PlaybackState.Unavailable,"Unavailable sample condemned route");
        Check(PlaybackDiagnostic.MediaUri("https://r1.googlevideo.com/videoplayback?sig=secret"),"Allowed media rejected");
        foreach(var url in new[]{"http://r1.googlevideo.com/videoplayback","https://googlevideo.com.evil.test/videoplayback","https://user@r1.googlevideo.com/videoplayback","https://r1.googlevideo.com:8443/videoplayback","https://r1.googlevideo.com/videoplayback#secret"})Check(!PlaybackDiagnostic.MediaUri(url),"Untrusted media accepted");
        var diagnostic=new PlaybackDiagnostic((r,t)=>Task.FromResult(new PlaybackResponse(200,"text/html","consent/login"u8.ToArray())),(h,t)=>Task.FromResult(Array.Empty<string>()));
        var report=await diagnostic.RunAsync(null,CancellationToken.None);
        Check(report.Contains("МЕДИА: НЕ ПРОВЕРЕНО") && !report.Contains("МЕДИА: ОБРАЗЕЦ ЗАГРУЖЕН"),"No player produced false success");
        var multi=PlaybackDiagnostic.Player("ytInitialPlayerResponse = {\"streamingData\":{\"formats\":[{\"mimeType\":\"video/mp4\",\"url\":\"https://r1.googlevideo.com/videoplayback?v=1\"}],\"adaptiveFormats\":[{\"mimeType\":\"video/mp4\",\"url\":\"https://r2.googlevideo.com/videoplayback?v=2\"},{\"mimeType\":\"audio/mp4\",\"url\":\"https://r3.googlevideo.com/videoplayback?a=1\"}]}};");
        Check(PlaybackDiagnostic.Samples(multi).Select(t=>t.Kind).SequenceEqual(new[]{"video","audio"}),"Separate audio lost behind video");
        bool changed=false;int mixed=0;
        var moving=new PlaybackDiagnostic((r,t)=> {if(r.Host=="www.youtube.com")changed=true;if(r.Path.StartsWith("/videoplayback"))mixed++;return Task.FromResult(new PlaybackResponse(200,"text/html",[]));},(h,t)=>Task.FromResult(Array.Empty<string>()),()=>{if(changed)throw new OperationCanceledException("route changed");});
        try {await moving.RunAsync(null,CancellationToken.None);throw new Exception("Mixed route report completed");}catch(OperationCanceledException) { }
        Check(mixed==0,"Old-route sample fetched after network change");
        int requests=0;
        var real=new PlaybackDiagnostic((r,t)=> {
            if(r.Host.EndsWith(".googlevideo.com") && r.Path.StartsWith("/videoplayback")) {requests++;return Task.FromResult(new PlaybackResponse(206,"video/mp4",bytes));}
            return Task.FromResult(new PlaybackResponse(200,"text/html",System.Text.Encoding.UTF8.GetBytes("ytInitialPlayerResponse = {\"streamingData\":{\"formats\":[{\"mimeType\":\"video/mp4\",\"url\":\"https://r1.googlevideo.com/videoplayback?sig=PRIVATE\"}]}};")));
        },(h,t)=>Task.FromResult(Array.Empty<string>()));
        report=await real.RunAsync(19080,CancellationToken.None);
        Check(requests>0 && report.Contains("65536") && !report.Contains("PRIVATE") && !report.Contains("?sig="),"Missing transfer or signed query leaked");
        using var cancel=new CancellationTokenSource();cancel.Cancel();
        try {await real.RunAsync(null,cancel.Token);throw new Exception("Cancellation swallowed");}catch(OperationCanceledException) { }
        Console.WriteLine("Playback diagnostics: URL allowlist, actual bytes/speed, unavailable discovery, privacy and cancellation passed");
    }
}
