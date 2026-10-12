$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$log = Join-Path $PSScriptRoot "tv-admute.log"
# keep the log from growing unbounded
if ((Test-Path $log) -and ((Get-Item $log).Length -gt 1MB)) { Clear-Content $log }
"=== $(Get-Date -Format o) ===" | Out-File -Append -Encoding utf8 $log
# adb must be on PATH; the Android SDK platform-tools folder is added as a fallback
$env:PATH += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
$py = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
# TV_IP comes from the user environment, see README
& $py -u "tv_admute.py" 2>&1 | ForEach-Object { "$_" } | Out-File -Append -Encoding utf8 $log

