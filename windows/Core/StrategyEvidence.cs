namespace DpiPilot;

public sealed record StrategyEvidence
{
    public string Id { get; init; } = "";
    public string Engine { get; init; } = CatalogProtocol.Engine;
    public int Passed { get; init; }
    public long Latency { get; init; }
    public DateTimeOffset CheckedAt { get; init; }
    public bool Excluded { get; init; }
    public bool ForceCheck { get; init; }
    public string LastFailure { get; init; } = "";
    public bool Complete => Passed == 6 && Latency > 0 && Engine == CatalogProtocol.Engine && CheckedAt > DateTimeOffset.MinValue;
    public ProbeResult Result => new(Complete,Passed,Latency,"Кэш 6/6 · "+CheckedAt.ToLocalTime().ToString("dd.MM HH:mm"));
}

/// All mutations use the settings lock; readers receive immutable record snapshots.
public sealed class StrategyEvidenceStore(SettingsStore settings)
{
    private static List<StrategyEvidence> Rows(Settings s,string profile) => s.Evidence.GetValueOrDefault(profile) ?? [];
    public IReadOnlyList<StrategyEvidence> Choices(string profile) => settings.Read(s => Rows(s,profile)
        .Where(r => r is not null && r.Complete && !r.Excluded).OrderBy(r=>r.Latency).ThenBy(r=>r.Id).ToArray());
    public StrategyEvidence? Fresh(string profile,string id,DateTimeOffset now) => settings.Read(s => Rows(s,profile)
        .FirstOrDefault(r=>r is not null && r.Id==id && r.Complete && !r.Excluded && !r.ForceCheck && now>=r.CheckedAt && now-r.CheckedAt<TimeSpan.FromDays(3)));
    public bool Excluded(string profile,string id) => settings.Read(s=>Rows(s,profile).Any(r=>r is not null && r.Id==id && r.Excluded));
    public bool Record(string profile,Strategy strategy,ProbeResult result,DateTimeOffset now)
    {
        if(!result.Complete || strategy.Id!=CatalogProtocol.Id(strategy.Args) || !CatalogProtocol.ValidCommand(strategy.Args))return false;
        bool accepted=false;
        settings.Change(s=> {
            var rows=Rows(s,profile).Where(r=>r is not null).ToList();var prior=rows.FirstOrDefault(r=>r.Id==strategy.Id);
            if(prior?.Excluded==true)return;
            rows.RemoveAll(r=>r.Id==strategy.Id);
            rows.Add(new(){Id=strategy.Id,Passed=6,Latency=result.Latency,CheckedAt=now});s.Evidence[profile]=rows;accepted=true;
        });return accepted;
    }
    private void Edit(string profile,string id,Func<StrategyEvidence,StrategyEvidence> update) => settings.Change(s=> {
        var rows=Rows(s,profile).Where(r=>r is not null).ToList();var prior=rows.FirstOrDefault(r=>r.Id==id) ?? new(){Id=id};
        rows.RemoveAll(r=>r.Id==id);rows.Add(update(prior));s.Evidence[profile]=rows;
    });
    public void Defer(string profile,string id,string reason) => Edit(profile,id,r=>r with {LastFailure=reason[..Math.Min(reason.Length,480)]});
    public void Reject(string profile,string id,string reason) => Edit(profile,id,r=>r with {Excluded=true,LastFailure=reason[..Math.Min(reason.Length,480)]});
    public void ResetExclusions(string profile) => settings.Change(s=>s.Evidence[profile]=Rows(s,profile).Where(r=>r is not null)
        .Select(r=>r.Excluded ? r with {Excluded=false,Passed=0,ForceCheck=true} : r).ToList());
    public void Expire(string profile) => settings.Change(s=>s.Evidence[profile]=Rows(s,profile).Where(r=>r is not null).Select(r=>r with {ForceCheck=true}).ToList());
    public void StampAttempt(string profile,DateTimeOffset now) => settings.Change(s=>s.ScanStarted[profile]=now);
    public bool Due(string profile,DateTimeOffset now) => settings.Read(s=>!s.ScanStarted.TryGetValue(profile,out var at) || (now>=at && now-at>=TimeSpan.FromDays(3)));
    public DateTimeOffset NextDue(string profile,bool periodic,DateTimeOffset now) => !periodic ? DateTimeOffset.MaxValue : settings.Read(s=>
        s.ScanStarted.TryGetValue(profile,out var at) ? (at>now ? now.AddDays(3) : at.AddDays(3)) : DateTimeOffset.MinValue);
}
