; Установщик Windows (Inno Setup 6). Собирается в CI: ISCC /DAppVersion=1.2.3 /DSourceDir=... stranitsa.iss
#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\build\compose\binaries\main-release\app\Stranitsa"
#endif

[Setup]
; Тот же AppId у всех версий: новая ставится поверх старой.
AppId={{6E0E6A1D-5C1B-4D84-9E55-2E7A3B0C7A11}
AppName=Страница
AppVersion={#AppVersion}
AppVerName=Страница {#AppVersion}
AppPublisher=Stranitsa
AppPublisherURL=https://github.com/Human9283/Stranitsa
AppSupportURL=https://github.com/Human9283/Stranitsa/issues
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\Stranitsa
DefaultGroupName=Страница
; Без прав администратора, в профиль пользователя; можно выбрать установку для всех.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DisableProgramGroupPage=yes
DisableDirPage=auto
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=..\icon\stranitsa.ico
UninstallDisplayIcon={app}\Stranitsa.exe
WizardStyle=modern
WizardImageFile=wizard.bmp
WizardSmallImageFile=wizard-small.bmp
Compression=lzma2/ultra64
SolidCompression=yes
OutputDir=..\build\installer
OutputBaseFilename=Stranitsa-Setup-{#AppVersion}
; Если программа запущена (например, обновляется сама), установщик попросит её закрыть.
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Страница"; Filename: "{app}\Stranitsa.exe"
Name: "{autodesktop}\Страница"; Filename: "{app}\Stranitsa.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Stranitsa.exe"; Description: "{cm:LaunchProgram,Страница}"; Flags: nowait postinstall skipifsilent

[CustomMessages]
russian.KeepData=Удалить также ваши списки и настройки?%n%nЕсли ответить «Нет», данные останутся в папке %1 и появятся снова при новой установке.
english.KeepData=Also delete your lists and settings?%n%nIf you answer No, your data stays in %1 and will be there when you install again.

[Code]
// При удалении спрашиваем, что делать с данными: по умолчанию они остаются.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{userappdata}\Stranitsa');
    if DirExists(DataDir) and (not UninstallSilent) then
      if MsgBox(FmtMessage(CustomMessage('KeepData'), [DataDir]), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(DataDir, True, True, True);
  end;
end;
