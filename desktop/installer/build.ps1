# Установщик Windows: собирает образ программы, упаковывает его Inno Setup и проверяет результат:
# тихо устанавливает, смотрит, что программа и значки на месте, запускает её, удаляет.
# Запуск (из папки desktop): pwsh installer/build.ps1 -Version 1.2.3
param([Parameter(Mandatory = $true)][string]$Version)
$ErrorActionPreference = 'Stop'
$repo = if ($env:GITHUB_REPOSITORY) { $env:GITHUB_REPOSITORY } else { 'Human9283/Stranitsa' }

./gradlew createReleaseDistributable "-PappVersion=$Version" "-PupdateRepo=$repo" --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'createReleaseDistributable failed' }

$iscc = (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source
if (-not $iscc) {
    $iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
    if (-not (Test-Path $iscc)) {
        choco install innosetup -y --no-progress
        if ($LASTEXITCODE -ne 0) { throw 'cannot install Inno Setup' }
    }
}
& $iscc "/DAppVersion=$Version" "/DSourceDir=$PWD\build\compose\binaries\main-release\app\Stranitsa" installer\stranitsa.iss
if ($LASTEXITCODE -ne 0) { throw 'ISCC failed' }

$setup = Get-ChildItem build\installer\Stranitsa-Setup-*.exe | Select-Object -First 1
if (-not $setup) { throw 'installer not found' }
Write-Host ("Установщик: {0} ({1:N1} МБ)" -f $setup.Name, ($setup.Length / 1MB))

# --- проверка: у человека стоит старая версия с установщиком MSI (до 1.0.3) ---
$oldMsi = Join-Path $env:TEMP 'stranitsa-old.msi'
$oldDir = Join-Path $env:LOCALAPPDATA 'Stranitsa'
try {
    Invoke-WebRequest 'https://github.com/Human9283/Stranitsa/releases/download/v1.0.2/Stranitsa-1.0.2.msi' -OutFile $oldMsi
    Start-Process msiexec.exe -ArgumentList '/i', $oldMsi, '/qn' -Wait
    if (-not (Test-Path (Join-Path $oldDir 'Stranitsa.exe'))) { throw 'старая версия MSI не установилась (для проверки)' }
    Write-Host 'Для проверки установлена старая версия 1.0.2 (MSI).'
} catch { Write-Host "::warning::проверку замены старой версии пропускаю: $_"; $oldMsi = $null }

# --- проверка: установка без окон ---
$log = Join-Path $env:RUNNER_TEMP 'stranitsa-install.log'
if (-not $env:RUNNER_TEMP) { $log = Join-Path $env:TEMP 'stranitsa-install.log' }
Start-Process $setup.FullName -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/TASKS=desktopicon', "/LOG=$log" -Wait
$app = Join-Path $env:LOCALAPPDATA 'Programs\Stranitsa'
$exe = Join-Path $app 'Stranitsa.exe'
$desktopLnk = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Страница.lnk'
$menuLnk = Join-Path ([Environment]::GetFolderPath('Programs')) 'Страница.lnk'
foreach ($p in @($exe, $desktopLnk, $menuLnk, (Join-Path $app 'unins000.exe'))) {
    if (-not (Test-Path $p)) { Get-Content $log -ErrorAction SilentlyContinue | Select-Object -Last 40; throw "после установки нет: $p" }
}
Write-Host 'Установка: программа, значок на рабочем столе и в меню «Пуск» на месте.'
if ($oldMsi) {
    if (Test-Path (Join-Path $oldDir 'Stranitsa.exe')) { throw 'старая установка MSI не убрана' }
    Write-Host 'Старая версия (MSI) убрана, программа не стоит дважды.'
}

# --- программа запускается и не падает ---
$p = Start-Process $exe -PassThru
Start-Sleep -Seconds 12
if ($p.HasExited) { throw "программа закрылась сама, код $($p.ExitCode)" }
$ws = [math]::Round((Get-Process -Id $p.Id).WorkingSet64 / 1MB)
Write-Host "Программа работает, занимает в памяти $ws МБ."
Stop-Process -Id $p.Id -Force

# --- удаление ---
Start-Process (Join-Path $app 'unins000.exe') -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait
Start-Sleep -Seconds 2
if (Test-Path $exe) { throw 'после удаления программа осталась' }
if (Test-Path $desktopLnk) { throw 'после удаления остался значок на рабочем столе' }
Write-Host 'Удаление: всё убрано.'
