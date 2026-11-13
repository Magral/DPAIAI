using DpiPilot;

internal static class ControllerCacheTests
{
    private static async Task Until(Func<bool> condition,string message,int seconds=25)
    {
        using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(seconds));
        while(!condition()) { if(deadline.IsCancellationRequested)throw new Exception(message);await Task.Delay(20); }
    }
    public static async Task RunAsync(string resources)
    {
        string directory=Path.Combine(Path.GetTempPath(),"dpi-controller-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(directory);
        try {
            var settings=new SettingsStore(directory);
            var catalog=new CatalogStore(settings,File.ReadAllText(Path.Combine(resources,"catalog-cert.pem")),File.ReadAllBytes(Path.Combine(resources,"catalog-v1.json")));
            settings.Change(s=> {s.CatalogChecked=DateTimeOffset.UtcNow;s.CatalogAttempt=DateTimeOffset.UtcNow;});
            var bank=catalog.Snapshot.Entries;var a=bank[1];var b=bank[2];var c=bank[3];
            int probes=0;string? blockedId=null;
            var entered=new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            var never=new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            await using var pilot=new PilotController(settings,catalog,Path.Combine(resources,"ciadpi.exe"),0,0,async(engine,token)=> {
                Interlocked.Increment(ref probes);
                if(engine.Strategy.Id==blockedId) {entered.TrySetResult(true);await never.Task.WaitAsync(token);}
                return engine.Strategy.Id==c.Id ? new ProbeResult(true,6,20,"full") : new ProbeResult(true,3,1,"partial");
            },networkProfile:()=>"host-fixture",networkAvailable:()=>true);
            var profile=pilot.NetworkProfile;var evidence=new StrategyEvidenceStore(settings);
            var now=DateTimeOffset.UtcNow;evidence.Record(profile,a,new ProbeResult(true,6,150,"full"),now);evidence.Record(profile,b,new ProbeResult(true,6,40,"full"),now);
            evidence.StampAttempt(profile,now);settings.Change(s=>s.History[profile]=[a.Id]);
            await pilot.StartAsync();
            await Until(()=>pilot.Phase.Contains("кэш"),"Cached startup did not settle");
            if(probes!=0 || !pilot.ActiveDescription.StartsWith(a.Id))throw new Exception("Startup re-probed or replaced the saved route");
            pilot.SelectStrategy(b.Id);
            await Until(()=>pilot.ActiveDescription.StartsWith(b.Id),"Fresh manual choice did not apply");
            if(probes!=0)throw new Exception("Fresh manual choice performed a network probe");
            evidence.Expire(profile);pilot.SelectStrategy(a.Id);
            await Until(()=>pilot.Phase.Contains("проверена: 6/6") || pilot.Phase.Contains("не подтверждена"),"Stale selected candidate did not finish");
            if(!pilot.ActiveDescription.StartsWith(b.Id))throw new Exception("A mislabeled 3/6 result replaced the working route");
            evidence.Record(profile,b,new ProbeResult(true,6,40,"full"),DateTimeOffset.UtcNow);pilot.SelectStrategy(b.Id);
            await Until(()=>pilot.Phase.Contains("из кэша"),"Cached route not restored after partial manual result");
            int beforeRestart=probes;await pilot.StopAsync();await pilot.StartAsync();
            await Until(()=>pilot.ActiveDescription.StartsWith(b.Id) && pilot.Phase.Contains("кэш"),"Restart lost selected strategy/cache");
            if(probes!=beforeRestart)throw new Exception("Restart re-probed fresh evidence");
            pilot.RequestCheck();
            await Until(()=>pilot.ActiveDescription.StartsWith(c.Id) && pilot.Phase.Contains("повторного теста нет"),"Automatic selection did not choose the lowest full latency");
            if(!evidence.Choices(profile).Any(r=>r.Id==c.Id) || evidence.Choices(profile).Any(r=>r.Id!=a.Id && r.Id!=b.Id && r.Id!=c.Id))throw new Exception("Partial candidate entered full choices");
            blockedId=bank[Math.Max(0,settings.Value.ScanCursor)%bank.Count].Id;pilot.RequestCheck(true);
            await Until(()=>entered.Task.IsCompleted,"Blocking candidate was not reached");
            evidence.Record(profile,b,new ProbeResult(true,6,40,"full"),DateTimeOffset.UtcNow);
            pilot.SelectStrategy(b.Id);
            await Until(()=>pilot.ActiveDescription.StartsWith(b.Id),"Manual cached choice waited for an obsolete long-running probe",3);
            if(evidence.Choices(profile).Any(r=>r.Id==blockedId))throw new Exception("Superseded probe committed stale evidence");
            await pilot.StopAsync();
        } finally { Directory.Delete(directory,true); }
        Console.WriteLine("Controller: startup/manual/restart cache reuse; lowest full latency; partial results rejected");
    }
}
