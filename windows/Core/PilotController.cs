using System.Collections.Concurrent;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace DpiPilot;

public sealed class PilotController : IAsyncDisposable
{
    private readonly SettingsStore settings;
    private readonly CatalogStore catalog;
    private readonly string executable;
    private readonly NativeJob job = new();
    private readonly CancellationTokenSource lifetime = new();
    private readonly SemaphoreSlim operation = new(1);
    private readonly ConcurrentQueue<string> journal = new();
    private readonly StrategyEvidenceStore evidence;
    private readonly Func<EngineProcess,CancellationToken,Task<ProbeResult>> runProbe;
    private readonly object routeGate = new();
    private EngineProcess? active;
    private CancellationTokenSource? enabledLifetime;
    private Task? worker;
    private Task? catalogWorker;
    private volatile bool requested;
    private volatile bool manualRequest;
    private long revision;
    private CancellationTokenSource? scanLifetime;
    private readonly Func<string>? getProfile;
    private readonly Func<bool> hasNetwork;
    private string activeProfile="";
    private bool disposed;
    public LocalGateway Gateway { get; }
    public bool Enabled => settings.Value.Enabled;
    public string Phase { get; private set; } = "Обход выключен; обычный маршрут Chrome";
    public string LastProbe { get; private set; } = "Проверок ещё не было";
    public string LastSuccess { get; private set; } = "нет";
    public string Observation => Gateway.Active==0 ? "Нет активных соединений YouTube; это не ошибка" : $"Соединений YouTube: {Gateway.Active}; получено {Gateway.Received/1024} КБ. Счётчики не запускают сетевые проверки.";
    public string ActiveDescription { get { lock(routeGate) return active is { } e ? $"{e.Strategy.Id}\n{(e.Strategy.Args == "" ? "Без модификаций" : e.Strategy.Args)}\nИсточник: {e.Strategy.Origin}" : "Без обхода"; } }
    public string[] Journal => journal.ToArray();
    public PilotController(SettingsStore settings,CatalogStore catalog,string executable,int proxyPort = 18471,int pacPort = 18472,Func<EngineProcess,CancellationToken,Task<ProbeResult>>? probe=null,Func<string>? networkProfile=null,Func<bool>? networkAvailable=null)
    {
        this.settings = settings; this.catalog = catalog; this.executable = executable;evidence=new(settings);runProbe=probe ?? Probe.CheckAsync;getProfile=networkProfile;hasNetwork=networkAvailable ?? NetworkInterface.GetIsNetworkAvailable;
        Gateway = new(proxyPort,pacPort,settings.Value.PacToken,ConnectAsync,Log);
        Log("Локальный обработчик: только YouTube/TCP443. Системные proxy/DNS/маршруты не меняются.");
        catalogWorker = UpdateCatalogAsync();
        if(OperatingSystem.IsWindows())NetworkChange.NetworkAddressChanged += NetworkChanged;
    }
    public void Log(string text) { journal.Enqueue(DateTime.Now.ToString("HH:mm:ss")+" "+text); while (journal.Count > 120) journal.TryDequeue(out _); }
    private async Task<ConnectionRoute> ConnectAsync(string host,int port,CancellationToken cancellation)
    {
        EngineProcess? engine; IDisposable? lease;
        lock (routeGate) { engine = active; lease = engine?.Acquire(); }
        if (engine is not null && lease is not null)
        {
            try { return new(await Socks.ConnectAsync(host,port,engine.Port,cancellation),lease); }
            catch { lease.Dispose(); throw; }
        }
        if (Enabled) throw new IOException("YouTube engine is starting");
        var direct = new TcpClient { NoDelay = true };
        try { await direct.ConnectAsync(host,port,cancellation); return new(direct); } catch { direct.Dispose(); throw; }
    }
    public async Task StartAsync()
    {
        await operation.WaitAsync(lifetime.Token);
        try
        {
            if (worker is { IsCompleted:false }) return;
            settings.Change(s => s.Enabled = true); requested = true;
            enabledLifetime?.Dispose(); enabledLifetime = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
            worker = WorkAsync(enabledLifetime.Token); Log("Авторежим включён");
        }
        finally { operation.Release(); }
    }
    public async Task StopAsync()
    {
        await operation.WaitAsync();
        try
        {
            settings.Change(s => s.Enabled = false); enabledLifetime?.Cancel(); Gateway.DropYouTubeConnections();
            if (worker is not null) try { await worker; } catch (OperationCanceledException) { }
            lock(routeGate) { active?.Dispose(); active = null; }
            job.KillChildren();
            Phase = "Обход выключен; обычный маршрут Chrome"; Log(Phase);
        }
        finally { operation.Release(); }
    }
    public IReadOnlyList<StrategyEvidence> KnownStrategies() => evidence.Choices(Profile());
    public bool IsFresh(string id) => evidence.Fresh(Profile(),id,DateTimeOffset.UtcNow) is not null;
    public void RequestCheck(bool force=false) {
        var profile=Profile();if(force)evidence.Expire(profile);
        settings.Change(s=>s.ManualChoices.Remove(profile));Interlocked.Increment(ref revision);CancelScan();manualRequest=true;requested=true;
    }
    public void SelectStrategy(string id) {
        var profile=Profile();
        if(!evidence.Choices(profile).Any(r=>r.Id==id) || !catalog.Snapshot.Entries.Any(s=>s.Id==id))throw new InvalidOperationException("Нет полного результата 6/6 для этой стратегии в текущей сети");
        settings.Change(s=>s.ManualChoices[profile]=id);Interlocked.Increment(ref revision);CancelScan();manualRequest=true;requested=true;
    }
    private void CancelScan() {try {Volatile.Read(ref scanLifetime)?.Cancel();}catch(ObjectDisposedException) {}}
    public void ResetExclusions() { evidence.ResetExclusions(Profile());Log("Исключения текущей сети сброшены; сетевой тест не запускался"); }

    public string NetworkProfile => Profile();
    private string Profile()
    {
        if(getProfile is not null)return getProfile();
        var parts = NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().GatewayAddresses.Select(a => a.Address.ToString()).Concat(n.GetIPProperties().DnsAddresses.Select(a=>a.ToString()))).Order().ToArray();
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join('|',parts))))[..24];
    }
    private void Swap(EngineProcess replacement)
    {
        lock(routeGate) { var old = active; active = replacement; old?.Retire(); }
        // Existing streams retain their immutable engine lease until they finish.
    }
    private void Remember(string profile,Strategy strategy)
    {
        settings.Change(s => { var prior = s.History.GetValueOrDefault(profile) ?? []; s.History[profile] = new[] {strategy.Id}.Concat(prior).Distinct().Take(3).ToList(); if (s.History.Count > 32) s.History.Remove(s.History.Keys.First()); });
        LastSuccess = evidence.Choices(profile).FirstOrDefault(r=>r.Id==strategy.Id)?.CheckedAt.ToLocalTime().ToString("dd.MM HH:mm:ss") ?? "нет";
    }
    private async Task WorkAsync(CancellationToken cancellation)
    {
        var next=DateTimeOffset.MinValue;
        try {
            while(!cancellation.IsCancellationRequested) {
                if(!hasNetwork()) { Phase="Нет физической сети; результаты сохранены";await Task.Delay(5000,cancellation);continue; }
                if(!requested && active?.Alive==true && (!settings.Value.PeriodicChecks || DateTimeOffset.UtcNow<next)) { await Task.Delay(1000,cancellation);continue; }
                bool manual=manualRequest;manualRequest=false;requested=false;
                long generation=Interlocked.Read(ref revision);
                string profile="";
                using var scan=CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                Volatile.Write(ref scanLifetime,scan);var token=scan.Token;
                bool Valid() => !scan.IsCancellationRequested && Enabled && generation==Interlocked.Read(ref revision) && Profile()==profile;
                try {
                    profile=Profile();
                    var bank=catalog.Snapshot.Entries;
                    var history=settings.Read(s=>s.History.GetValueOrDefault(profile)?.ToArray() ?? []);
                    var pin=settings.Read(s=>s.ManualChoices.GetValueOrDefault(profile));
                    Strategy? pinned=bank.FirstOrDefault(s=>s.Id==pin && !evidence.Excluded(profile,s.Id));
                    if(active is null || !active.Alive || activeProfile!=profile) {
                        var first=(pinned is not null && evidence.Fresh(profile,pinned.Id,DateTimeOffset.UtcNow) is not null ? pinned : null)
                            ?? history.Select(id=>bank.FirstOrDefault(s=>s.Id==id && !evidence.Excluded(profile,id))).FirstOrDefault(s=>s is not null)
                            ?? evidence.Choices(profile).Select(r=>bank.FirstOrDefault(s=>s.Id==r.Id)).FirstOrDefault(s=>s is not null)
                            ?? bank.FirstOrDefault(s=>s.Args.StartsWith("--timeout 8 --cache-ttl 600",StringComparison.Ordinal) && !evidence.Excluded(profile,s.Id))
                            ?? bank.First(s=>!evidence.Excluded(profile,s.Id));
                        var started=await EngineProcess.StartAsync(executable,first,job,token);
                        if(!Valid()) { started.Dispose();continue; }
                        Swap(started);activeProfile=profile;
                    }
                    var previous=active!;
                    var now=DateTimeOffset.UtcNow;
                    if(!manual && (pinned is null || pinned.Id==previous.Strategy.Id) && !evidence.Due(profile,now)) {
                        var proof=evidence.Fresh(profile,previous.Strategy.Id,now);
                        Phase=proof is not null ? "Включено · свежий кэш 6/6, повторных проверок нет" : "Маршрут сохранён · следующий подбор по расписанию или кнопке";
                        if(proof is not null)LastProbe=proof.Result.Detail;
                        next=evidence.NextDue(profile,settings.Value.PeriodicChecks,now);continue;
                    }
                    if(pinned is not null) {
                        var proof=evidence.Fresh(profile,pinned.Id,now);
                        EngineProcess? candidate=null;
                        try {
                            var target=previous.Strategy.Id==pinned.Id ? previous : candidate=await EngineProcess.StartAsync(executable,pinned,job,token);
                            var result=proof?.Result;
                            if(result is null) { evidence.StampAttempt(profile,now);result=await runProbe(target,token);if(!Valid())continue;if(result.Complete)evidence.Record(profile,pinned,result,DateTimeOffset.UtcNow);else evidence.Defer(profile,pinned.Id,result.Detail); }
                            if(!Valid())continue;
                            LastProbe=result.Detail;
                            if(result.Complete) {
                                if(candidate is not null) { Swap(candidate);candidate=null; }
                                Remember(profile,pinned);Phase=proof is not null ? "Ручная стратегия применена из кэша без повторного теста" : "Ручная стратегия проверена: 6/6";
                            } else Phase="Выбранная стратегия не подтверждена; прежний маршрут сохранён";
                        } finally { candidate?.Dispose(); }
                        next=evidence.NextDue(profile,settings.Value.PeriodicChecks,DateTimeOffset.UtcNow);continue;
                    }
                    var currentProof=evidence.Fresh(profile,previous.Strategy.Id,now);
                    ProbeResult current;
                    DateTimeOffset? livePositive=null;
                    if(currentProof is not null) current=currentProof.Result;
                    else {
                        evidence.StampAttempt(profile,now);current=await runProbe(previous,token);if(!Valid())continue;
                        if(current.Complete) { livePositive=DateTimeOffset.UtcNow;evidence.Record(profile,previous.Strategy,current,livePositive.Value); }
                        else evidence.Defer(profile,previous.Strategy.Id,current.Detail);
                    }
                    LastProbe=current.Detail;
                    var failures=new List<(Strategy Strategy,ProbeResult Result,DateTimeOffset At)>();
                    if(!current.Complete)failures.Add((previous.Strategy,current,DateTimeOffset.UtcNow));
                    var priority=history.Take(3).Concat(evidence.Choices(profile).Take(3).Select(r=>r.Id))
                        .Concat(bank.Where(s=>s.Args.StartsWith("--timeout 8 --cache-ttl 600",StringComparison.Ordinal)).Take(1).Select(s=>s.Id));
                    var cursor=Math.Max(0,settings.Value.ScanCursor)%bank.Count;
                    var order=priority.Concat(bank.Skip(cursor).Concat(bank.Take(cursor)).Select(s=>s.Id)).Distinct()
                        .Select(id=>bank.FirstOrDefault(s=>s.Id==id)).Where(s=>s is not null && s.Id!=previous.Strategy.Id && !evidence.Excluded(profile,s.Id))
                        .Select(s=>s!).Take(CheckPolicy.Candidates(settings.Value.PeriodicChecks,manual)).ToArray();
                    for(int i=0;i<order.Length;i++) {
                        if(!Valid())break;var strategy=order[i];
                        Phase=$"Подбор {i+1}/{order.Length} · база {bank.Count} · текущий маршрут сохранён";
                        var proof=evidence.Fresh(profile,strategy.Id,DateTimeOffset.UtcNow);
                        if(proof is not null) { Log(strategy.Id+": свежий кэш 6/6");continue; }
                        EngineProcess? candidate=null;
                        try {
                            candidate=await EngineProcess.StartAsync(executable,strategy,job,token);
                            evidence.StampAttempt(profile,DateTimeOffset.UtcNow);
                            var result=await runProbe(candidate,token);if(!Valid())break;
                            LastProbe=result.Detail;Log(strategy.Id+": "+result.Detail);
                            settings.Change(s=>s.ScanCursor=(bank.ToList().FindIndex(e=>e.Id==strategy.Id)+1)%bank.Count);
                            if(result.Complete) { livePositive=DateTimeOffset.UtcNow;evidence.Record(profile,strategy,result,livePositive.Value); }
                            else { evidence.Defer(profile,strategy.Id,result.Detail);failures.Add((strategy,result,DateTimeOffset.UtcNow)); }
                        } catch(OperationCanceledException) when(cancellation.IsCancellationRequested) { throw; }
                        catch(Exception e) when(e is IOException or SocketException or InvalidOperationException) { if(Valid())evidence.Defer(profile,strategy.Id,e.GetType().Name); }
                        finally { candidate?.Dispose(); }
                    }
                    if(!Valid())continue;
                    // Only a real later success in this cycle can confirm a preceding conclusive failure.
                    if(livePositive is { } control) foreach(var failure in failures.Where(f=>!f.Result.Inconclusive && f.At<=control))
                        evidence.Reject(profile,failure.Strategy.Id,failure.Result.Detail);
                    var fresh=evidence.Choices(profile).Where(r=>bank.Any(s=>s.Id==r.Id) && evidence.Fresh(profile,r.Id,DateTimeOffset.UtcNow) is not null).ToArray();
                    var best=fresh.OrderBy(r=>r.Latency).ThenBy(r=>r.Id==previous.Strategy.Id ? 0 : 1).FirstOrDefault();
                    if(best is not null) {
                        var strategy=bank.First(s=>s.Id==best.Id);
                        if(previous.Strategy.Id!=best.Id) {
                            var replacement=await EngineProcess.StartAsync(executable,strategy,job,token);
                            if(!Valid()) { replacement.Dispose();continue; }
                            Swap(replacement);
                        }
                        Remember(profile,strategy);LastProbe=best.Result.Detail;Phase=$"Включено · 6/6 · {best.Latency} мс · повторного теста нет";
                    } else Phase="Полного результата нет; рабочий процесс и прежние результаты сохранены";
                    next=evidence.NextDue(profile,settings.Value.PeriodicChecks,DateTimeOffset.UtcNow);
                    if(next<=DateTimeOffset.UtcNow)next=settings.Value.PeriodicChecks ? DateTimeOffset.UtcNow.AddDays(3) : DateTimeOffset.MaxValue;
                } catch(OperationCanceledException) when(cancellation.IsCancellationRequested) { throw; }
                catch(OperationCanceledException) when(!cancellation.IsCancellationRequested) { requested=true; }
                catch(Exception e) { Log("Подбор: "+e.GetType().Name);Phase="Ошибка проверки; прежний маршрут сохранён";next=settings.Value.PeriodicChecks ? DateTimeOffset.UtcNow.AddDays(3) : DateTimeOffset.MaxValue; }
                finally { Interlocked.CompareExchange(ref scanLifetime,null,scan); }
            }
        } catch(OperationCanceledException) when(cancellation.IsCancellationRequested) { }
    }
    private void NetworkChanged(object? sender,EventArgs args) { Interlocked.Increment(ref revision);CancelScan();requested=true; }
    private async Task UpdateCatalogAsync()
    {
        try { while(!lifetime.IsCancellationRequested) { await catalog.RefreshAsync(false,lifetime.Token); await Task.Delay(TimeSpan.FromMinutes(15),lifetime.Token); } }
        catch(OperationCanceledException) when(lifetime.IsCancellationRequested) { }
    }
    public async ValueTask DisposeAsync()
    {
        if (disposed) return; disposed=true; if(OperatingSystem.IsWindows())NetworkChange.NetworkAddressChanged -= NetworkChanged; lifetime.Cancel(); enabledLifetime?.Cancel(); Gateway.DropYouTubeConnections();
        if (worker is not null) await worker;
        if (catalogWorker is not null) await catalogWorker;
        lock(routeGate) { active?.Dispose(); active=null; }
        await Gateway.DisposeAsync(); job.Dispose(); enabledLifetime?.Dispose(); lifetime.Dispose();
    }
}
