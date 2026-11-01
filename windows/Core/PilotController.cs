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
    private readonly ConcurrentDictionary<string,DateTimeOffset> blocked = new();
    private readonly object routeGate = new();
    private EngineProcess? active;
    private CancellationTokenSource? enabledLifetime;
    private Task? worker;
    private Task? observer;
    private Task? catalogWorker;
    private volatile bool requested;
    private volatile bool stalled;
    private volatile bool trialInProgress;
    private volatile bool manualRequest;
    private long usefulAt,watchAt,lastSent,lastReceived;
    private int deficient;
    private bool disposed;
    public LocalGateway Gateway { get; }
    public bool Enabled => settings.Value.Enabled;
    public string Phase { get; private set; } = "Обход выключен; обычный маршрут Chrome";
    public string LastProbe { get; private set; } = "Проверок ещё не было";
    public string LastSuccess { get; private set; } = "нет";
    public string Observation { get; private set; } = "Ожидание трафика YouTube из Chrome";
    public string ActiveDescription { get { lock(routeGate) return active is { } e ? $"{e.Strategy.Id}\n{(e.Strategy.Args == "" ? "Без модификаций" : e.Strategy.Args)}\nИсточник: {e.Strategy.Origin}" : "Без обхода"; } }
    public string[] Journal => journal.ToArray();
    public PilotController(SettingsStore settings,CatalogStore catalog,string executable,int proxyPort = 18471,int pacPort = 18472)
    {
        this.settings = settings; this.catalog = catalog; this.executable = executable;
        Gateway = new(proxyPort,pacPort,settings.Value.PacToken,ConnectAsync,Log);
        Log("Локальный обработчик: только YouTube/TCP443. Системные proxy/DNS/маршруты не меняются.");
        observer = ObserveAsync();
        catalogWorker = UpdateCatalogAsync();
        NetworkChange.NetworkAddressChanged += NetworkChanged;
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
    public void RequestCheck() { manualRequest=true; requested = true; }
    private string Profile()
    {
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
        LastSuccess = DateTime.Now.ToString("dd.MM HH:mm:ss");
    }
    private static bool Useful(long when) => when > 0 && Environment.TickCount64-when < 90000;
    private async Task WorkAsync(CancellationToken cancellation)
    {
        int failures = 0; DateTimeOffset next = DateTimeOffset.MinValue;
        try
        {
            while (!cancellation.IsCancellationRequested)
            {
                if (!NetworkInterface.GetIsNetworkAvailable()) { Phase = "Нет физического подключения; ожидаем сеть"; await Task.Delay(10000,cancellation); continue; }
                if (!CheckPolicy.ShouldProbe(settings.Value.PeriodicChecks,requested,stalled,active?.Alive == true,DateTimeOffset.UtcNow,next)) { await Task.Delay(settings.Value.PeriodicChecks ? 1000 : 10000,cancellation); continue; }
                requested = false; bool manual = manualRequest; manualRequest=false; bool wasStalled = stalled; stalled = false;
                try
                {
                    string profile = Profile(); var bank = catalog.Snapshot.Entries;
                    var history = settings.Value.History.GetValueOrDefault(profile) ?? [];
                    if (active is null || !active.Alive)
                    {
                        Strategy first = history.Select(id => bank.FirstOrDefault(s => s.Id==id)).FirstOrDefault(s=>s is not null) ?? bank.First(s => s.Args.StartsWith("--timeout 8 --cache-ttl 600",StringComparison.Ordinal));
                        Phase = "Запускается сохранённая/адаптивная стратегия";
                        Swap(await EngineProcess.StartAsync(executable,first,job,cancellation));
                    }
                    var previous = active!; Phase = "Проверка активной стратегии";
                    ProbeResult current = await Probe.CheckAsync(previous,cancellation); LastProbe = current.Detail; Log("Текущая: "+current.Detail);
                    if (current.Ok && !wasStalled) { Phase = "Включено · проверки YouTube пройдены"; failures=0; Remember(profile,previous.Strategy); next=CheckPolicy.Next(settings.Value.PeriodicChecks,DateTimeOffset.UtcNow); continue; }
                    if (!wasStalled && Useful(Interlocked.Read(ref usefulAt))) { Phase="YouTube получает данные; сохраняем работающий поток"; next=DateTimeOffset.UtcNow.AddMinutes(1); continue; }
                    var order = history.Concat(bank.Where(s=>s.Args.StartsWith("--timeout 8 --cache-ttl 600",StringComparison.Ordinal)).Select(s=>s.Id))
                        .Concat(bank.Skip(settings.Value.ScanCursor % bank.Count).Concat(bank.Take(settings.Value.ScanCursor % bank.Count)).Select(s=>s.Id)).Distinct()
                        .Select(id=>bank.FirstOrDefault(s=>s.Id==id)).Where(s=>s is not null && s.Id != previous.Strategy.Id && (!blocked.TryGetValue(s.Id,out var until) || until < DateTimeOffset.UtcNow)).Take(CheckPolicy.Candidates(settings.Value.PeriodicChecks,manual)).ToArray();
                    bool changed = false;
                    foreach (var strategy in order)
                    {
                        cancellation.ThrowIfCancellationRequested();
                        if (Useful(Interlocked.Read(ref usefulAt)) && !wasStalled) break;
                        EngineProcess? candidate = null;
                        try
                        {
                            Phase = "Фоновый тест: "+strategy!.Id+"; текущий поток сохранён";
                            candidate = await EngineProcess.StartAsync(executable,strategy,job,cancellation);
                            var result = await Probe.CheckAsync(candidate,cancellation); LastProbe=result.Detail; Log(strategy.Id+": "+result.Detail);
                            settings.Change(s => s.ScanCursor = (bank.ToList().FindIndex(e=>e.Id==strategy.Id)+1)%bank.Count);
                            if (!result.Ok) { blocked[strategy.Id] = DateTimeOffset.UtcNow.AddMinutes(30); continue; }
                            if (Useful(Interlocked.Read(ref usefulAt)) && !wasStalled) break;
                            // Retain the previous process during the trial so rollback does not restart it.
                            using var backupLease = previous.Acquire();
                            lock(routeGate) active = candidate;
                            var trial = candidate; candidate = null;
                            Phase = "Пробная стратегия; наблюдение за новыми соединениями до 2 минут";
                            Log(Phase); trialInProgress=true; bool rollback = false,trialFailed = false; long trialAt = Environment.TickCount64;
                            for (int t=0;t<12;t++)
                            {
                                await Task.Delay(10000,cancellation);
                                if (!trial.Alive || (stalled && Environment.TickCount64-trialAt >= 45000)) { trialFailed=true; rollback = current.Ok && !wasStalled && previous.Alive; break; }
                                if ((settings.Value.PeriodicChecks && t == 2) || t == 11)
                                {
                                    var check = await Probe.CheckAsync(trial,cancellation);
                                    if (!check.Ok) { var repeat = await Probe.CheckAsync(trial,cancellation); if (!repeat.Ok) { trialFailed=true; rollback = current.Ok && !wasStalled && previous.Alive; break; } }
                                }
                            }
                            trialInProgress=false;
                            if (rollback)
                            { lock(routeGate) active = previous; trial.Dispose(); blocked[strategy.Id]=DateTimeOffset.UtcNow.AddMinutes(30); Phase="Откат к предыдущей стратегии"; Log(Phase); }
                            else if(trialFailed)
                            {
                                lock(routeGate) active=previous.Alive ? previous : null;
                                trial.Dispose(); blocked[strategy.Id]=DateTimeOffset.UtcNow.AddMinutes(30);requested=true;
                                Phase="Пробная стратегия не подтверждена; сохранена прежняя или ожидается новый подбор";Log(Phase);
                            }
                            else { previous.Retire(); Remember(profile,strategy); Phase="Включено · стратегия проверена"; Log(Phase); changed=true; }
                            break;
                        }
                        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { throw; }
                        catch (Exception e) when (e is IOException or SocketException or InvalidOperationException)
                        { blocked[strategy!.Id]=DateTimeOffset.UtcNow.AddMinutes(30); Log("Кандидат: "+e.GetType().Name); }
                        finally { trialInProgress=false; candidate?.Dispose(); }
                    }
                    failures = changed ? 0 : failures+1;
                    if (!changed) Phase="Обработка включена; полная доступность YouTube пока не подтверждена";
                    next = settings.Value.PeriodicChecks ? DateTimeOffset.UtcNow.AddMinutes(changed ? 60 : 10) : DateTimeOffset.MaxValue;
                }
                catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { throw; }
                catch (Exception e) { Phase="Ошибка проверки: "+e.GetType().Name+"; повтор через минуту"; Log(Phase); next=settings.Value.PeriodicChecks ? DateTimeOffset.UtcNow.AddMinutes(10) : DateTimeOffset.MaxValue; }
            }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
    }
    private async Task ObserveAsync()
    {
        long priorRx=0;
        try
        {
            while (!lifetime.IsCancellationRequested)
            {
                await Task.Delay(10000,lifetime.Token);
                if (!settings.Value.PeriodicChecks && !trialInProgress) { Observation="Тихий режим: проверка при запуске, смене сети или по кнопке"; continue; }
                long now=Environment.TickCount64, rx=Gateway.Received, tx=Gateway.Sent;
                if (rx-priorRx >= 256*1024) Interlocked.Exchange(ref usefulAt,now); priorRx=rx;
                if (watchAt == 0 || now-watchAt > 90000 || !NetworkInterface.GetIsNetworkAvailable() || !Enabled) { watchAt=now; lastSent=tx; lastReceived=rx; deficient=0; }
                if (now-watchAt >= 45000)
                {
                    bool lack = Gateway.Active > 0 && tx-lastSent >= 16*1024 && rx-lastReceived < 64*1024;
                    deficient=lack ? deficient+1 : 0;
                    Observation = lack ? "Запросы идут, ответов мало; наблюдаем повторяемость" : Gateway.Active == 0 ? "Нет активных соединений YouTube; это не ошибка" : "Идёт обмен данными YouTube";
                    if (deficient>=2 && !Useful(Interlocked.Read(ref usefulAt))) { stalled=true; Observation="Устойчивый дефицит ответов; запрошен фоновый подбор"; deficient=0; }
                    watchAt=now; lastSent=tx; lastReceived=rx;
                }

            }
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
    }
    private void NetworkChanged(object? sender,EventArgs args) { requested=true; stalled=false; Interlocked.Exchange(ref usefulAt,0); }
    private async Task UpdateCatalogAsync()
    {
        try { while(!lifetime.IsCancellationRequested) { await catalog.RefreshAsync(false,lifetime.Token); await Task.Delay(TimeSpan.FromMinutes(15),lifetime.Token); } }
        catch(OperationCanceledException) when(lifetime.IsCancellationRequested) { }
    }
    public async ValueTask DisposeAsync()
    {
        if (disposed) return; disposed=true; NetworkChange.NetworkAddressChanged -= NetworkChanged; lifetime.Cancel(); enabledLifetime?.Cancel(); Gateway.DropYouTubeConnections();
        if (worker is not null) await worker;
        if (observer is not null) await observer;
        if (catalogWorker is not null) await catalogWorker;
        lock(routeGate) { active?.Dispose(); active=null; }
        await Gateway.DisposeAsync(); job.Dispose(); enabledLifetime?.Dispose(); lifetime.Dispose();
    }
}
