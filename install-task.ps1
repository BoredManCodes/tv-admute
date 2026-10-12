param([Parameter(Mandatory)][string]$TvIp)
# Registers a hidden logon task that keeps tv_admute.py running.
$ErrorActionPreference = "Stop"
$dir = $PSScriptRoot
[Environment]::SetEnvironmentVariable("TV_IP", $TvIp, "User")
$action = New-ScheduledTaskAction -Execute "wscript.exe" `
  -Argument "//nologo `"$dir\run-hidden.vbs`" `"$dir\run-tv-admute.ps1`"" -WorkingDirectory $dir
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
  -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName "TvAdMute" -Action $action -Trigger $trigger -Settings $settings `
  -Principal $principal -Description "Mutes the TV during 7plus ad breaks" -Force | Out-Null
Write-Host "Registered TvAdMute. Sign out and in, or run: Start-ScheduledTask TvAdMute"
