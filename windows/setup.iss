#define AppVersion "0.1.1"
[Setup]
AppId={{B0FC1F56-5ED3-4E29-B9C8-A511A227B103}
AppName=DPI Pilot YouTube
AppVersion={#AppVersion}
AppPublisher=Magral
AppPublisherURL=https://github.com/Magral/DPAIAI
DefaultDirName={localappdata}\Programs\DPI Pilot YouTube
DefaultGroupName=DPI Pilot YouTube
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir=release
OutputBaseFilename=DPI-Pilot-YouTube-Windows-{#AppVersion}-Setup
Compression=lzma2
SolidCompression=yes
UninstallDisplayIcon={app}\DPI-Pilot-YouTube.exe
CloseApplications=yes
RestartApplications=no
LicenseFile=..\LICENSE
[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
Source: "extension\*"; DestDir: "{app}\extension"; Flags: ignoreversion recursesubdirs
Source: "..\tv\src\main\assets\BYEDPI-LICENSE.txt"; DestDir: "{app}"
[Icons]
Name: "{group}\DPI Pilot YouTube"; Filename: "{app}\DPI-Pilot-YouTube.exe"
Name: "{autodesktop}\DPI Pilot YouTube"; Filename: "{app}\DPI-Pilot-YouTube.exe"
[Registry]
Root: HKCU; Subkey: "Software\Google\Chrome\NativeMessagingHosts\io.github.magral.dpipilot"; Flags: uninsdeletekey
[Run]
Filename: "{app}\DPI-Pilot-YouTube.exe"; Description: "Запустить DPI Pilot и установить расширение Chrome"; Flags: nowait postinstall skipifsilent
[UninstallRun]
Filename: "{app}\DPI-Pilot-YouTube.exe"; Parameters: "--uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "DpiPilotUnregister"
