param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference='Stop'
$app=$null;$hostProcess=$null
function Send-Frame($Process,$Value) {
  $bytes=[Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Compress))
  $stream=$Process.StandardInput.BaseStream
  $length=[BitConverter]::GetBytes([uint32]$bytes.Length)
  $stream.Write($length,0,4);$stream.Write($bytes,0,$bytes.Length);$stream.Flush()
}
function Read-Exact($Stream,[int]$Count) {
  $bytes=[byte[]]::new($Count);$offset=0
  while($offset -lt $Count) {
    $task=$Stream.ReadAsync($bytes,$offset,$Count-$offset)
    if(!$task.Wait(30000)){throw 'Native message timeout'}
    $received=$task.GetAwaiter().GetResult()
    if($received -eq 0){throw 'Native host closed output'}
    $offset+=$received
  }
  return ,$bytes
}
function Read-Frame($Process) {
  $header=Read-Exact $Process.StandardOutput.BaseStream 4
  $count=[BitConverter]::ToUInt32($header,0)
  if($count -eq 0 -or $count -gt 65536){throw 'Invalid native frame'}
  $body=Read-Exact $Process.StandardOutput.BaseStream $count
  return [Text.Encoding]::UTF8.GetString($body) | ConvertFrom-Json
}
try {
  $app=Start-Process -FilePath $Executable -ArgumentList '--background' -PassThru
  Start-Sleep -Seconds 3
  if($app.HasExited){throw 'Background GUI process exited during startup'}
  $manifest=(Get-ItemProperty 'HKCU:\Software\Google\Chrome\NativeMessagingHosts\io.github.magral.dpipilot').'(default)'
  if(!(Test-Path $manifest)){throw 'Native host not registered'}
  $registration=Get-Content $manifest -Raw | ConvertFrom-Json
  if($registration.allowed_origins[0] -ne 'chrome-extension://dfdbnmbhcnaojhfjfolfenecmmbiokhn/'){throw 'Native origin mismatch'}
  $info=[Diagnostics.ProcessStartInfo]::new($Executable)
  $info.UseShellExecute=$false;$info.CreateNoWindow=$true
  $info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
  $info.ArgumentList.Add('chrome-extension://dfdbnmbhcnaojhfjfolfenecmmbiokhn/')
  $hostProcess=[Diagnostics.Process]::Start($info)
  Send-Frame $hostProcess @{command='hello';launch=$false}
  $state=Read-Frame $hostProcess
  if(!$state.running -or $state.enabled){throw 'Unexpected initial native state'}
  if($state.pac -notmatch 'SOCKS5 127.0.0.1:18471'){throw 'Native response lacks scoped PAC'}
  Send-Frame $hostProcess @{command='status'}
  $state=Read-Frame $hostProcess
  if(!$state.running){throw 'Native status failed'}
  $stop=Start-Process -FilePath $Executable -ArgumentList '--uninstall' -PassThru -Wait
  if(!$app.WaitForExit(20000)){throw 'GUI did not shut down through its own IPC'}
  Send-Frame $hostProcess @{command='status'}
  $offline=Read-Frame $hostProcess
  if($offline.running){throw 'Offline helper reported running'}
  if(!$hostProcess.WaitForExit(5000)){throw 'Native host did not close after helper exit'}
  Write-Output 'Installed native messaging: actual WinExe stdio framing, current-user registration, GUI IPC, offline response and clean shutdown passed'
}
finally {
  if($hostProcess -and !$hostProcess.HasExited){$hostProcess.Kill($true)}
  if($app -and !$app.HasExited){$app.Kill($true)}
}
