# ---------------------------------------------------------------------------
# Builds dist\FullerScope Installer\ (and a .zip of it): the installer, the current
# APK and a copy of adb, ready to copy to any Windows PC.
#
#   powershell -ExecutionPolicy Bypass -File tools\installer\make_package.ps1 [-Build]
#
# -Build runs gradle assembleDebug first; without it the latest existing build is used.
# ---------------------------------------------------------------------------
param([switch]$Build)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools'
$Out = Join-Path $Root 'dist\FullerScope Installer'

if ($Build) {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    & (Join-Path $Root 'gradlew.bat') -p $Root :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
}
if (-not (Test-Path $Apk)) { throw "No APK at $Apk - run with -Build" }
if (-not (Test-Path (Join-Path $Sdk 'adb.exe'))) { throw "No adb at $Sdk" }

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Force (Join-Path $Out 'platform-tools') | Out-Null

foreach ($f in 'FullerScope Installer.ps1', 'FullerScope Installer.cmd', 'README.txt') {
    Copy-Item (Join-Path $PSScriptRoot $f) $Out
}
Copy-Item $Apk (Join-Path $Out 'FullerScope.apk')
# adb.exe plus the two DLLs it loads; NOTICE.txt carries the licences.
foreach ($f in 'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt') {
    Copy-Item (Join-Path $Sdk $f) (Join-Path $Out 'platform-tools')
}

$zip = "$Out.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $Out -DestinationPath $zip

$built = (Get-Item $Apk).LastWriteTime.ToString('yyyy-MM-dd HH:mm')
Write-Host "Packaged APK built $built"
Write-Host "Folder: $Out"
Write-Host "Zip:    $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"
