using System.Security.Cryptography;
using System.Text.Json;

namespace DpiPilot;

public sealed class Settings
{
    public bool Enabled { get; set; }
    public string PacToken { get; set; } = Convert.ToHexString(RandomNumberGenerator.GetBytes(20)).ToLowerInvariant();
    public Dictionary<string, List<string>> History { get; set; } = [];
    public long CatalogSequence { get; set; }
    public string CatalogDigest { get; set; } = "";
    public DateTimeOffset CatalogChecked { get; set; }
    public DateTimeOffset CatalogAttempt { get; set; }
    public int ScanCursor { get; set; }
}

public sealed class SettingsStore
{
    public string DirectoryPath { get; }
    public Settings Value { get; }
    private readonly object gate = new();
    public SettingsStore(string directory)
    {
        DirectoryPath = directory; Directory.CreateDirectory(directory);
        try { Value = JsonSerializer.Deserialize<Settings>(File.ReadAllText(Path.Combine(directory, "settings.json"))) ?? new(); }
        catch (Exception e) when (e is IOException or JsonException) { Value = new(); }
        if (Value.PacToken.Length != 40 || Value.PacToken.Any(c => !char.IsAsciiHexDigit(c))) Value.PacToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(20));
        Save();
    }
    public void Change(Action<Settings> change) { lock (gate) { change(Value); Save(); } }
    public void Save() { lock (gate) { AtomicWrite(Path.Combine(DirectoryPath, "settings.json"), JsonSerializer.SerializeToUtf8Bytes(Value)); } }
    public static void AtomicWrite(string path, byte[] bytes)
    {
        string temporary = path + ".new";
        using (var output = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None)) { output.Write(bytes); output.Flush(true); }
        File.Move(temporary, path, true);
    }
}
