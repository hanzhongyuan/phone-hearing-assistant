param([switch]$InstallSdk)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$env:GRADLE_USER_HOME = Join-Path $projectDir '.tools\gradle-home'
$env:ANDROID_HOME = Join-Path $projectDir '.tools\sdk'
if (-not (Test-Path -LiteralPath (Join-Path $projectDir '.tools\gradle-8.11.1\bin\gradle.bat'))) { throw 'Run python scripts/bootstrap-local.py first.' }
if ($InstallSdk) {
    & (Join-Path $env:ANDROID_HOME 'cmdline-tools\bin\sdkmanager.bat') "--sdk_root=$env:ANDROID_HOME" --licenses
    & (Join-Path $env:ANDROID_HOME 'cmdline-tools\bin\sdkmanager.bat') "--sdk_root=$env:ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed.' }
}
python (Join-Path $PSScriptRoot 'fetch-models.py')
if ($LASTEXITCODE -ne 0) { throw 'Model download or verification failed.' }
& (Join-Path $projectDir '.tools\gradle-8.11.1\bin\gradle.bat') -p $projectDir :app:assembleRelease :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'Build or lint failed.' }
