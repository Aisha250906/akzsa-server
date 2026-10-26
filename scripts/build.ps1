# Builds AKZSA from the repository as-is. This script NEVER edits source files.
# Usage: .\scripts\build.ps1 [-Task test|debug|lint|release-aab|release-apk]
param([ValidateSet('test','debug','lint','release-aab','release-apk')][string]$Task = 'test')
$ErrorActionPreference = 'Stop'
$android = Join-Path $PSScriptRoot '..\AKZSA-Android'
Set-Location $android
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT -and -not (Test-Path 'local.properties')) {
  Write-Error 'Android SDK not found. Install Android Studio, set ANDROID_HOME, or create local.properties (sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk).'
}
$gradleTask = switch ($Task) {
  'test'        { ':app:testFullDebugUnitTest' }
  'debug'       { ':app:assembleFullDebug' }
  'lint'        { ':app:lintPlayRelease' }
  'release-aab' { ':app:bundlePlayRelease' }
  'release-apk' { ':app:assemblePlayRelease' }
}
& .\gradlew.bat --no-daemon $gradleTask
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
