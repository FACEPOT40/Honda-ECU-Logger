; H-ECU Logger installer (Inno Setup 6). Build it with build_installer.bat, not by hand:
; that script builds dist\H-ECU-Logger.exe first and passes the version from app.py.
;
; Installs the app into Program Files with Start menu (and optional desktop) shortcuts and an
; uninstall entry. Optionally adds FTDI's official signed VCP driver (the CDM-v... folder next to
; this project, not in git) with Windows' own pnputil. Ride logs go to
; Documents\HondaECULogger\logs, because Program Files can't be written (recorder.default_log_dir).

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#define AppName "H-ECU Logger"
#define AppExe "H-ECU-Logger.exe"
#define DriverDir "..\CDM-v2.12.36.20-WHQL-Certified"

[Setup]
AppId={{7C2E4F1A-5B3D-4E8A-9F61-0D2B8A4C6E35}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} (K-line) {#AppVersion}
AppPublisher=FACEPOT40
AppPublisherURL=https://github.com/FACEPOT40/Honda-ECU-Logger
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
LicenseFile=..\LICENSE
OutputDir=..\dist
OutputBaseFilename=H-ECU-Logger-Setup-{#AppVersion}
SetupIconFile=..\assets\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
; Program Files and the driver both need administrator rights
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
Compression=lzma2
SolidCompression=yes

[Tasks]
Name: "driver"; Description: "Install the FTDI cable driver (needed once per PC for the K-line cable)"
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Files]
Source: "..\dist\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
; only the driver files (the .inf files point to amd64\ and i386\), not FTDI's developer headers and libraries
Source: "{#DriverDir}\*.inf"; DestDir: "{app}\driver"; Tasks: driver; Flags: ignoreversion
Source: "{#DriverDir}\*.cat"; DestDir: "{app}\driver"; Tasks: driver; Flags: ignoreversion
Source: "{#DriverDir}\amd64\*"; Excludes: "*.lib"; DestDir: "{app}\driver\amd64"; Tasks: driver; Flags: ignoreversion
Source: "{#DriverDir}\i386\*"; Excludes: "*.lib"; DestDir: "{app}\driver\i386"; Tasks: driver; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
; add both FTDI driver parts to Windows' driver store and install them for a plugged-in cable
Filename: "{sys}\pnputil.exe"; Parameters: "/add-driver ""{app}\driver\ftdibus.inf"" /install"; \
  StatusMsg: "Installing the FTDI cable driver (1/2)..."; Tasks: driver; Flags: runhidden waituntilterminated
Filename: "{sys}\pnputil.exe"; Parameters: "/add-driver ""{app}\driver\ftdiport.inf"" /install"; \
  StatusMsg: "Installing the FTDI cable driver (2/2)..."; Tasks: driver; Flags: runhidden waituntilterminated
Filename: "{app}\{#AppExe}"; Description: "Start {#AppName} now"; Flags: nowait postinstall skipifsilent

[Messages]
FinishedLabel=Setup has finished installing [name] on your computer.%n%nPlug in the K-line cable, then pick its COM port in the app. Ride logs are saved in Documents\HondaECULogger\logs.%n%nRead only: the app never writes to the ECU. Never look at the screen while riding.
