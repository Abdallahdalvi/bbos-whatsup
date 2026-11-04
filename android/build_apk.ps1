$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'JDK 17 is required (java not found).' }
$javaVersion = (& java -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "17\.') { throw "JDK 17 is required. Found: $javaVersion" }
if (-not (Test-Path (Join-Path $sdk 'platforms\android-34\android.jar'))) { throw "Android SDK Platform 34 not found under $sdk" }
$tools = Join-Path $sdk 'build-tools\34.0.0'
$apksigner = Join-Path $tools 'apksigner.bat'
$aapt = Join-Path $tools 'aapt.exe'
if (-not (Test-Path $apksigner) -or -not (Test-Path $aapt)) { throw 'Android Build Tools 34.0.0 (aapt, apksigner) are required.' }
$env:ANDROID_SDK_ROOT = $sdk
Push-Location $project
try {
    & .\gradlew.bat --no-daemon clean compileDebugJavaWithJavac processDebugResources assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    $output = Join-Path $project 'output'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $apk = Join-Path $output 'Q20Messenger.apk'
    Copy-Item -LiteralPath (Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apk -Force
    & $apksigner verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $badging = & $aapt dump badging $apk
    if (-not ($badging -match "sdkVersion:'18'")) { throw 'APK minimum SDK is not 18.' }
    if (-not ($badging -match "targetSdkVersion:'18'")) { throw 'APK target SDK is not 18.' }
    $badging | Select-String 'package: name='
    Write-Host "Built and verified $apk"
} finally { Pop-Location }
