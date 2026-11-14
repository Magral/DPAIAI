using DpiPilot;
using System.Text;

namespace DpiPilot;

internal sealed class PlaybackDialog : Form
{
    private readonly PilotController pilot;
    private readonly TextBox output=new(){Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Both,Dock=DockStyle.Fill,WordWrap=true};
    private readonly Button system=new(){Text="Текущая сеть / платный VPN",AutoSize=true};
    private readonly Button engine=new(){Text="Работающий DPI Pilot",AutoSize=true};
    private CancellationTokenSource? running;
    public PlaybackDialog(PilotController pilot)
    {
        this.pilot=pilot;Text="YouTube: диагностика меню и видео";Size=new(1000,720);StartPosition=FormStartPosition.CenterParent;
        var layout=new TableLayoutPanel{Dock=DockStyle.Fill,RowCount=3,ColumnCount=1,Padding=new(14)};
        layout.RowStyles.Add(new(SizeType.AutoSize));layout.RowStyles.Add(new(SizeType.AutoSize));layout.RowStyles.Add(new(SizeType.Percent,100));
        layout.Controls.Add(new Label{AutoSize=true,MaximumSize=new(900,0),Text="Тест по требованию, до нескольких минут. Настройки, VPN и стратегии не меняются. Сравните обычную сеть и платный VPN через «Текущая сеть». Внешний VPN должен включать DPI Pilot, одного VPN-расширения Chrome для этого теста недостаточно. 8/8 — доступность узлов; медиадиапазон проверяется отдельно."});
        var actions=new FlowLayoutPanel{AutoSize=true,Dock=DockStyle.Fill};
        system.Click+=async(_,_)=>await Start(false);engine.Click+=async(_,_)=>await Start(true);
        actions.Controls.Add(system);actions.Controls.Add(engine);
        var cancel=new Button{Text="Остановить",AutoSize=true};cancel.Click+=(_,_)=>running?.Cancel();actions.Controls.Add(cancel);
        var copy=new Button{Text="Копировать",AutoSize=true};copy.Click+=(_,_)=>{if(output.Text.Length>0)Clipboard.SetText(output.Text);};actions.Controls.Add(copy);
        var save=new Button{Text="Сохранить отчёт",AutoSize=true};save.Click+=(_,_)=>{using var file=new SaveFileDialog{FileName="DPI-Pilot-YouTube-diagnostic.txt",Filter="Текстовый отчёт|*.txt"};if(file.ShowDialog(this)==DialogResult.OK)File.WriteAllText(file.FileName,output.Text,Encoding.UTF8);};actions.Controls.Add(save);
        layout.Controls.Add(actions);layout.Controls.Add(output);Controls.Add(layout);
    }
    private async Task Start(bool throughEngine)
    {
        if(running is not null)return;
        using var cancellation=new CancellationTokenSource();running=cancellation;system.Enabled=false;engine.Enabled=false;
        string meta=$"DPI Pilot · {DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss zzz}\r\nПуть: {(throughEngine ? "локальный движок YouTube" : "Windows без локального обхода")}\r\n";
        output.Text=meta;
        try {
            string report=await pilot.DiagnosePlaybackAsync(throughEngine,cancellation.Token,line=>{if(!IsDisposed)output.AppendText(line+Environment.NewLine);});
            if(!IsDisposed)output.Text=meta+report;
        } catch(OperationCanceledException) {if(!IsDisposed)output.AppendText("\r\nТЕСТ ОСТАНОВЛЕН ИЛИ СЕТЬ/VPN ИЗМЕНИЛИСЬ; итог не определён, запустите новый тест");}
        catch(Exception e) {if(!IsDisposed)output.AppendText("\r\nТест не завершён: "+e.GetType().Name);}
        finally {running=null;if(!IsDisposed){system.Enabled=true;engine.Enabled=true;}}
    }
    protected override void OnFormClosing(FormClosingEventArgs e) {running?.Cancel();base.OnFormClosing(e);}
}
