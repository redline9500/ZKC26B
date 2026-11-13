# Optional local CLI build. Requires Android SDK 35 from Android Studio and an internet connection.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path (Join-Path $sdk 'platforms\android-35\android.jar'))) {
    throw "Android SDK Platform 35 not found in $sdk. Install it using Android Studio > SDK Manager first."
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$gradleCmd = Get-Command gradle -ErrorAction SilentlyContinue
if ($gradleCmd) {
    & $gradleCmd.Source ':app:assembleDebug' '--no-daemon'
} else {
    $version = '8.9'
    $dir = Join-Path $env:TEMP 'zkc26-gradle'
    $zip = Join-Path $dir "gradle-$version-bin.zip"
    $exe = Join-Path $dir "gradle-$version\bin\gradle.bat"
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    if (-not (Test-Path $exe)) {
        if (-not (Test-Path $zip)) {
            Write-Host "Downloading Gradle $version from the official Gradle distribution service..."
            Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $zip
        }
        Expand-Archive -Path $zip -DestinationPath $dir -Force
    }
    & $exe ':app:assembleDebug' '--no-daemon'
}
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
$out = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $out)) { throw "Build completed but APK not found at $out" }
Write-Host "APK created: $out"
