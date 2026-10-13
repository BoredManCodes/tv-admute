param(
    [Parameter(Mandatory)][string]$TvIp,
    [int]$Port = 5555,
    [switch]$NoBuild
)
# Builds the app, installs it on the TV and switches on its accessibility service
# next to any services that are already enabled (the remote may depend on one).
$ErrorActionPreference = "Stop"
$adb = "${TvIp}:$Port"
$pkg = "com.boredmancodes.tvadmute"
$svc = "$pkg/$pkg.AdMuteService"
if (-not $NoBuild) { & "$PSScriptRoot\overlay\build.ps1" }
adb -s $adb install -r "$PSScriptRoot\overlay\build\tv-admute-overlay.apk"
if ($LASTEXITCODE) { throw "adb install failed; is the TV authorised? (adb connect $adb)" }

$cur = (adb -s $adb shell settings get secure enabled_accessibility_services | Out-String).Trim()
$others = ($cur -split ":" | Where-Object { $_ -and $_ -ne "null" -and $_ -ne $svc }) -join ":"
# A reinstall leaves the setting unchanged and Android may not reconnect the service,
# so take it out and put it back to force a rebind.
adb -s $adb shell settings put secure enabled_accessibility_services $(if ($others) { $others } else { "null" })
Start-Sleep 2
$new = if ($others) { "${others}:$svc" } else { $svc }
adb -s $adb shell settings put secure enabled_accessibility_services $new
adb -s $adb shell settings put secure accessibility_enabled 1
Start-Sleep 3
Write-Host "Enabled services: $(adb -s $adb shell settings get secure enabled_accessibility_services)"
Write-Host "Test (covers the screen and mutes for 10 seconds):"
Write-Host "  adb -s $adb shell am broadcast -a $pkg.DEBUG --es cmd show --ei secs 10"
Write-Host "  adb -s $adb shell am broadcast -a $pkg.DEBUG --es cmd hide"
