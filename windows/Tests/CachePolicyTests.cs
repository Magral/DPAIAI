using DpiPilot;

internal static class CachePolicyTests
{
    public static void Run()
    {
        var now=DateTimeOffset.FromUnixTimeSeconds(1800000000);
        if(CheckPolicy.Next(true,now)!=now.AddDays(3))throw new Exception("Periodic checks must use a 72-hour interval, not hourly probes");
        if(CheckPolicy.Next(false,now)!=DateTimeOffset.MaxValue)throw new Exception("Quiet mode must remain quiet");
        string directory=Path.Combine(Path.GetTempPath(),"dpi-evidence-"+Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var settings=new SettingsStore(directory);var store=new StrategyEvidenceStore(settings);
            var a=new Strategy(CatalogProtocol.Id("--split 1"),"--split 1","test");
            var b=new Strategy(CatalogProtocol.Id("--split 2"),"--split 2","test");
            if(store.Record("wifi-a",a,new ProbeResult(true,3,1,"partial"),now))throw new Exception("Partial proof accepted");
            if(store.Choices("wifi-a").Count!=0)throw new Exception("Partial choice leaked");
            if(!store.Record("wifi-a",a,new ProbeResult(true,6,150,"full"),now))throw new Exception("Full proof rejected");
            store.Record("wifi-a",b,new ProbeResult(true,6,50,"full"),now);
            if(store.Choices("wifi-a")[0].Id!=b.Id)throw new Exception("Latency ordering failed");
            if(store.Record("wifi-a",b,new ProbeResult(true,6,1,"inconclusive",true),now))throw new Exception("Inconclusive proof accepted");
            store.Expire("wifi-a");
            if(store.Fresh("wifi-a",b.Id,now)!=null || store.Choices("wifi-a").First(r=>r.Id==b.Id).CheckedAt!=now)throw new Exception("Force recheck changed timestamp or reused proof");
            store.Record("wifi-a",a,new ProbeResult(true,6,150,"full"),now);
            settings.Change(s=>s.Evidence["other-engine"]=[new StrategyEvidence{Id=a.Id,Passed=6,Latency=1,CheckedAt=now,Engine="other"}]);
            if(store.Fresh("other-engine",a.Id,now)!=null || store.Choices("other-engine").Count!=0)throw new Exception("Other engine proof reused");
            store.Defer("wifi-a",a.Id,"temporary timeout");
            if(store.Fresh("wifi-a",a.Id,now.AddHours(1)) is null)throw new Exception("Transient failure erased proof");
            if(store.Fresh("wifi-b",a.Id,now)!=null || store.Fresh("wifi-a",a.Id,now.AddSeconds(-1))!=null)throw new Exception("Network/clock isolation failed");
            if(store.Fresh("wifi-a",a.Id,now.AddDays(3))!=null)throw new Exception("Expired proof remained fresh");
            var restored=new StrategyEvidenceStore(new SettingsStore(directory));
            if(restored.Fresh("wifi-a",a.Id,now.AddDays(3).AddTicks(-1))?.CheckedAt!=now)throw new Exception("Restart or cache reuse renewed/lost timestamp");
            restored.Reject("wifi-a",a.Id,"confirmed failure");
            if(restored.Fresh("wifi-a",a.Id,now)!=null)throw new Exception("Excluded proof reused");
            restored.ResetExclusions("wifi-a");
            if(restored.Fresh("wifi-a",a.Id,now)!=null)throw new Exception("Reset fabricated fresh evidence");
            restored.StampAttempt("wifi-a",now);
            if(restored.Due("wifi-a",now.AddDays(3).AddSeconds(-1)))throw new Exception("Restart reset the 72-hour budget");
            if(!new StrategyEvidenceStore(new SettingsStore(directory)).Due("wifi-a",now.AddDays(3)))throw new Exception("72-hour scan never became due");
        }
        finally { Directory.Delete(directory,true); }
    }
}
