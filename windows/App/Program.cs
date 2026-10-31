using System.Reflection;
using System.Security.Cryptography;
using System.Text;

namespace DpiPilot;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if(args.Any(a=>a.StartsWith("chrome-extension://",StringComparison.Ordinal)))
        {
            if(args[0]!="chrome-extension://"+NativeBridge.ExtensionId+"/") return;
            try { NativeBridge.RunHostAsync().GetAwaiter().GetResult(); } catch(Exception) { }
            return;
        }
        if(args.Contains("--package-check")){PackageCheck();return;}
        if(args.Contains("--uninstall")){Uninstall();return;}
        ApplicationConfiguration.Initialize();
        using var mutex=new Mutex(true,"Local\\"+NativeBridge.PipeName,out bool created);
        using var activate=new EventWaitHandle(false,EventResetMode.AutoReset,"Local\\"+NativeBridge.PipeName+".show");
        if(!created) { if(!args.Contains("--background"))activate.Set();return; }
        try
        {
            string data=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"DPI-Pilot-YouTube");
            var settings=new SettingsStore(data);
            string executable=ExtractEngine(data);
            var catalog=new CatalogStore(settings,Encoding.UTF8.GetString(Resource("catalog-cert.pem")),Resource("catalog-v1.json"));
            NativeBridge.Register();
            using var form=new MainForm(settings,catalog,executable,args.Contains("--background"));
            var registration=ThreadPool.RegisterWaitForSingleObject(activate,(_,_)=>form.BeginInvoke(()=>form.ShowPanel()),null,Timeout.Infinite,false);
            try { Application.Run(form); } finally { registration.Unregister(null); }
        }
        catch(Exception e) { MessageBox.Show("Не удалось запустить DPI Pilot: "+e.Message,"DPI Pilot",MessageBoxButtons.OK,MessageBoxIcon.Error); }
        finally { mutex.ReleaseMutex(); }
    }
    private static void PackageCheck()
    {
        string result=Environment.GetEnvironmentVariable("DPI_PILOT_TEST_RESULT") ?? Path.Combine(Path.GetTempPath(),"dpi-package-check.txt");
        try {
            string directory=Path.Combine(Path.GetTempPath(),"DpiPilot-package-"+Guid.NewGuid());
            var settings=new SettingsStore(directory);
            var catalog=new CatalogStore(settings,Encoding.UTF8.GetString(Resource("catalog-cert.pem")),Resource("catalog-v1.json"));
            string executable=ExtractEngine(directory);
            using var job=new NativeJob();
            using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(20));
            using var engine=EngineProcess.StartAsync(executable,catalog.Snapshot.Entries[1],job,timeout.Token).GetAwaiter().GetResult();
            using var frame=new MemoryStream();
            NativeBridge.WriteAsync(frame,Encoding.UTF8.GetBytes("{\"command\":\"status\"}"),timeout.Token).GetAwaiter().GetResult();frame.Position=0;
            byte[] packet=NativeBridge.ReadAsync(frame,timeout.Token).GetAwaiter().GetResult()!;
            if(Encoding.UTF8.GetString(packet)!="{\"command\":\"status\"}")throw new InvalidDataException("Native framing");
            File.WriteAllText(result,"OK: embedded public signature/catalog/native engine and native messaging framing");
        }catch(Exception e){File.WriteAllText(result,e.ToString());Environment.ExitCode=1;}
    }
    private static void Uninstall()
    {
        try {
            using var pipe=new System.IO.Pipes.NamedPipeClientStream(".",NativeBridge.PipeName,System.IO.Pipes.PipeDirection.InOut);
            pipe.Connect(1000);
            NativeBridge.WriteAsync(pipe,System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new{command="shutdown"}),CancellationToken.None).GetAwaiter().GetResult();
        }catch(Exception){ }
        using(var key=Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run",true))key?.DeleteValue("DpiPilotYouTube",false);
        using var host=Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Google\Chrome\NativeMessagingHosts\"+NativeBridge.Host);
        if(host?.GetValue("") as string == Path.Combine(AppContext.BaseDirectory,"native-host.json"))
            Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(@"Software\Google\Chrome\NativeMessagingHosts\"+NativeBridge.Host,false);
    }
    internal static byte[] Resource(string name)
    { using var input=Assembly.GetExecutingAssembly().GetManifestResourceStream("Payload."+name) ?? throw new FileNotFoundException(name);using var output=new MemoryStream();input.CopyTo(output);return output.ToArray(); }
    private static string ExtractEngine(string data)
    {
        byte[] bytes=Resource("ciadpi.exe");string hash=Convert.ToHexString(SHA256.HashData(bytes));
        string directory=Path.Combine(data,"engine",hash);Directory.CreateDirectory(directory);string file=Path.Combine(directory,"ciadpi.exe");
        if(!File.Exists(file)||!SHA256.HashData(File.ReadAllBytes(file)).SequenceEqual(SHA256.HashData(bytes)))SettingsStore.AtomicWrite(file,bytes);
        return file;
    }
}
