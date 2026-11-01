namespace DpiPilot;

public static class CheckPolicy
{
    public static bool ShouldProbe(bool periodic,bool requested,bool stalled,bool engineAlive,DateTimeOffset now,DateTimeOffset next)
        => requested || stalled || !engineAlive || (periodic && now >= next);
    public static DateTimeOffset Next(bool periodic,DateTimeOffset now) => periodic ? now.AddHours(1) : DateTimeOffset.MaxValue;
    public static int Candidates(bool periodic,bool manual) => periodic || manual ? 12 : 3;
}
