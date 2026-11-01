using System.Diagnostics;
using System.Text;
using Microsoft.Win32;

namespace DpiPilot;

internal sealed class MainForm : Form
{
    private readonly PilotController pilot;
    private readonly CatalogStore catalog;
    private readonly SettingsStore settings;
    private readonly ProxySafety safety=new();
    private readonly CancellationTokenSource lifetime=new();
    private readonly NotifyIcon tray;
    private readonly System.Windows.Forms.Timer timer=new(){Interval=1500};
    private readonly Label heading=new(){AutoSize=true,Font=new Font("Segoe UI",22,FontStyle.Bold)};
    private readonly Label phase=new(){AutoSize=true,Font=new Font("Segoe UI",13,FontStyle.Bold),MaximumSize=new Size(1000,0)};
    private readonly TextBox information=new(){Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Vertical,Dock=DockStyle.Fill,Font=new Font("Consolas",10)};
    private readonly Button toggle=new(){AutoSize=true};
    private bool exiting,cleaned;
    private long extensionSeen;
    public MainForm(SettingsStore settings,CatalogStore catalog,string executable,bool background)
    {
        this.settings=settings;this.catalog=catalog;
        pilot=new(settings,catalog,executable);
        Text="DPI Pilot · YouTube для Chrome · 0.1.1";MinimumSize=new(760,620);Size=new(1020,790);StartPosition=FormStartPosition.CenterScreen;
        AutoScaleMode=AutoScaleMode.Dpi;Font=new("Segoe UI",11);
        var layout=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=7,Padding=new(20)};
        layout.RowStyles.Add(new(SizeType.AutoSize));layout.RowStyles.Add(new(SizeType.AutoSize));layout.RowStyles.Add(new(SizeType.AutoSize));layout.RowStyles.Add(new(SizeType.AutoSize));layout.RowStyles.Add(new(SizeType.AutoSize));layout.RowStyles.Add(new(SizeType.AutoSize));layout.RowStyles.Add(new(SizeType.Percent,100));
        heading.Text="YouTube в вашем Chrome";layout.Controls.Add(heading);layout.Controls.Add(phase);
        layout.Controls.Add(new Label{AutoSize=true,MaximumSize=new(1000,0),Text="Расширение работает в обычных вкладках Chrome. Локальный обработчик принимает только YouTube/TCP 443. Windows, другие приложения и сетевые игры не перенастраиваются.",Padding=new(0,10,0,10)});
        var actions=new FlowLayoutPanel{AutoSize=true,Dock=DockStyle.Fill};
        void Button(string title,Action action){var b=new Button{Text=title,AutoSize=true,Padding=new(10,7,10,7),Margin=new(0,0,10,8)};b.Click+=(_,_)=>action();actions.Controls.Add(b);}
        toggle.Padding=new(10,7,10,7);toggle.Click+=async(_,_)=>{toggle.Enabled=false;try{if(pilot.Enabled)await pilot.StopAsync();else await pilot.StartAsync();}catch(Exception e){ShowError(e);}finally{toggle.Enabled=true;}};actions.Controls.Add(toggle);
        Button("Проверить YouTube",()=>pilot.RequestCheck());
        Button("Открыть YouTube",()=>OpenYouTube());
        Button("Обновить базу",()=>_=catalog.RefreshAsync(true,lifetime.Token));
        Button("Экспорт отчёта",Export);
        layout.Controls.Add(actions);
        var install=new FlowLayoutPanel{AutoSize=true,Dock=DockStyle.Fill};
        var extension=new Button{Text="Установить расширение в Chrome",AutoSize=true,Padding=new(8)};
        extension.Click+=(_,_)=>InstallExtension();install.Controls.Add(extension);
        var autorun=new CheckBox{Text="Запускать в трее при входе в Windows",AutoSize=true,Padding=new(10)};
        using(var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run"))autorun.Checked=key?.GetValue("DpiPilotYouTube") is string;
        autorun.CheckedChanged+=(_,_)=>{using var key=Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");if(autorun.Checked)key.SetValue("DpiPilotYouTube","\""+Environment.ProcessPath+"\" --background");else key.DeleteValue("DpiPilotYouTube",false);};
        install.Controls.Add(autorun);
        var periodic=new CheckBox{Text="Периодические проверки (раз в час)",AutoSize=true,Checked=settings.Value.PeriodicChecks,Padding=new(10)};
        periodic.CheckedChanged+=(_,_)=>{settings.Change(s=>s.PeriodicChecks=periodic.Checked);pilot.RequestCheck();};
        install.Controls.Add(periodic);layout.Controls.Add(install);
        layout.Controls.Add(new Label{AutoSize=true,Text="Закрытие окна сворачивает приложение в трей. Полный выход — через меню значка рядом с часами.",Padding=new(0,4,0,10)});
        layout.Controls.Add(information);Controls.Add(layout);
        var menu=new ContextMenuStrip();menu.Items.Add("Открыть",null,(_,_)=>ShowPanel());menu.Items.Add("Выход",null,async(_,_)=>await ExitAsync());
        tray=new(){Icon=SystemIcons.Application,Text="DPI Pilot · YouTube",Visible=true,ContextMenuStrip=menu};tray.DoubleClick+=(_,_)=>ShowPanel();
        timer.Tick+=(_,_)=>Render();timer.Start();
        Shown+=async(_,_)=>{if(background)Hide();if(settings.Value.Enabled)await pilot.StartAsync();};
        FormClosing+=(_,e)=>{if(!exiting && e.CloseReason!=CloseReason.WindowsShutDown){e.Cancel=true;Hide();}};
        _=NativeBridge.ServerAsync(CommandAsync,lifetime.Token);
        Render();
    }
    public void ShowPanel(){Show();WindowState=FormWindowState.Normal;Activate();}
    private async Task<object> CommandAsync(string name)
    {
        Interlocked.Exchange(ref extensionSeen,Environment.TickCount64);
        switch(name)
        {
            case "enable":await pilot.StartAsync();break;
            case "disable":await pilot.StopAsync();break;
            case "check":pilot.RequestCheck();break;
            case "refresh":_=catalog.RefreshAsync(true,lifetime.Token);break;
            case "openApp":BeginInvoke(()=>ShowPanel());break;
            case "shutdown":await pilot.StopAsync();BeginInvoke(async()=>{lifetime.Cancel();await pilot.DisposeAsync();exiting=true;Close();});break;
        }
        safety.Check();
        return new {running=true,enabled=pilot.Enabled,phase=pilot.Phase,strategy=pilot.ActiveDescription,pac=DomainPolicy.Pac(pilot.Gateway.ProxyPort),routingAllowed=safety.Allowed,routingReason=safety.Reason};
    }
    private void Render()
    {
        heading.Text=pilot.Enabled ? "YouTube · обработка включена" : "YouTube · обход выключен";
        phase.Text=pilot.Enabled && (Interlocked.Read(ref extensionSeen)==0 || Environment.TickCount64-Interlocked.Read(ref extensionSeen)>20000) ? "Компонент включён; подключите расширение в обычном Chrome" : pilot.Phase;toggle.Text=pilot.Enabled?"Выключить обход":"Включить обход";
        information.Text=Report();
    }
    private string Report()=>
        "DPI Pilot Windows 0.1.1 · Chrome extension\r\n"+
        "Расширение: "+(Interlocked.Read(ref extensionSeen)>0 && Environment.TickCount64-Interlocked.Read(ref extensionSeen)<20000 ? "связь есть (статус маршрута — на значке Chrome)" : "нет связи; проверьте одноразовую установку")+"\r\n"+
        "Маршрутизация: "+safety.Reason+"\r\n"+
        "Стратегия: "+pilot.ActiveDescription.Replace("\n","\r\n")+"\r\n\r\n"+
        $"База: {catalog.Snapshot.Entries.Count} совместимых с Windows вариантов · {catalog.Snapshot.Version}\r\n{catalog.Status}\r\n"+
        $"Проверена база: {settings.Value.CatalogChecked.LocalDateTime:dd.MM HH:mm:ss}\r\nПоследний успех YouTube: {pilot.LastSuccess}\r\n"+
        "Последняя проверка: "+pilot.LastProbe+"\r\nНаблюдение: "+pilot.Observation+"\r\n"+
        $"Соединений: {pilot.Gateway.Active}; принято: {pilot.Gateway.Accepted}; отклонено нецелевых: {pilot.Gateway.Rejected}\r\n"+
        $"Отправлено {pilot.Gateway.Sent/1048576.0:F2} МиБ; получено {pilot.Gateway.Received/1048576.0:F2} МиБ\r\n\r\n"+
        "Системный прокси, DNS, маршруты и UDP компьютера не изменяются. TLS не расшифровывается.\r\n"+
        "Доступность узлов и обмен данными не подтверждают воспроизведение каждого ролика.\r\n\r\nЖурнал:\r\n"+string.Join("\r\n",pilot.Journal);
    private void Export()
    {using var dialog=new SaveFileDialog{FileName="DPI-Pilot-report.txt",Filter="Текстовый отчёт|*.txt"};if(dialog.ShowDialog()==DialogResult.OK)File.WriteAllText(dialog.FileName,Report(),Encoding.UTF8);}
    private static void OpenYouTube(){string? chrome=FindChrome();if(chrome is null){MessageBox.Show("Установите Google Chrome");return;}Process.Start(new ProcessStartInfo(chrome){UseShellExecute=false,ArgumentList={"https://www.youtube.com"}});}
    private void InstallExtension()
    {
        string directory=Path.Combine(AppContext.BaseDirectory,"extension");Clipboard.SetText(directory);
        var chrome=FindChrome();if(chrome is not null){var start=new ProcessStartInfo(chrome){UseShellExecute=false};start.ArgumentList.Add("chrome://extensions");Process.Start(start);}
        Process.Start(new ProcessStartInfo("explorer.exe"){ArgumentList={directory},UseShellExecute=false});
        MessageBox.Show("1. В обычном Chrome откройте chrome://extensions.\n2. Включите «Режим разработчика».\n3. Нажмите «Загрузить распакованное расширение».\n4. Выберите папку, путь уже скопирован:\n\n"+directory+"\n\nЭто одноразовая установка. Отдельное окно/профиль Chrome создавать не нужно.","Установка расширения",MessageBoxButtons.OK,MessageBoxIcon.Information);
    }
    private static string? FindChrome()=>new[]{Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),@"Google\Chrome\Application\chrome.exe"),Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),@"Google\Chrome\Application\chrome.exe"),Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),@"Google\Chrome\Application\chrome.exe")}.FirstOrDefault(File.Exists);
    private static void ShowError(Exception error)=>MessageBox.Show(error.Message,"DPI Pilot",MessageBoxButtons.OK,MessageBoxIcon.Error);
    private async Task ExitAsync()
    {
        if(MessageBox.Show("Завершить DPI Pilot? Расширение освободит настройки Chrome после потери связи. Остальные приложения продолжат работать с прежними настройками.","Выход",MessageBoxButtons.YesNo,MessageBoxIcon.Question)!=DialogResult.Yes)return;
        await pilot.StopAsync();lifetime.Cancel();await pilot.DisposeAsync();exiting=true;tray.Visible=false;Close();
    }
    protected override void Dispose(bool disposing)
    {if(disposing && !cleaned){cleaned=true;timer.Dispose();tray.Dispose();safety.Dispose();lifetime.Cancel();lifetime.Dispose();}base.Dispose(disposing);}
}
