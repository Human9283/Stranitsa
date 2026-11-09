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
// Версии до 1.0.3 ставились установщиком MSI. Новый установщик другой, поэтому сам убирает
// старую установку (списки и настройки лежат отдельно и остаются), чтобы программа не стояла дважды.
function RemoveOldMsi(Root: Integer): Boolean;
var
  Keys: TArrayOfString;
  I, Code: Integer;
  Path, Name, Pub: String;
  Msi: Cardinal;
begin
  Result := False;
  if not RegGetSubkeyNames(Root, 'Software\Microsoft\Windows\CurrentVersion\Uninstall', Keys) then Exit;
  for I := 0 to GetArrayLength(Keys) - 1 do
  begin
    // у MSI-программ ключ — это GUID; у установок Inno имя заканчивается на _is1
    if (Length(Keys[I]) = 38) and (Keys[I][1] = '{') then
    begin
      Path := 'Software\Microsoft\Windows\CurrentVersion\Uninstall\' + Keys[I];
      if RegQueryStringValue(Root, Path, 'DisplayName', Name) and (Name = 'Stranitsa')
         and RegQueryStringValue(Root, Path, 'Publisher', Pub) and (Pub = 'Stranitsa')
         and RegQueryDWordValue(Root, Path, 'WindowsInstaller', Msi) and (Msi = 1) then
      begin
        Log('Удаляю старую установку MSI ' + Keys[I]);
        if Exec('msiexec.exe', '/x ' + Keys[I] + ' /qn /norestart', '', SW_HIDE, ewWaitUntilTerminated, Code) then
          Result := True;
      end;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
  begin
    RemoveOldMsi(HKCU);
    RemoveOldMsi(HKLM);
  end;
end;

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
