param([Parameter(Mandatory)][string]$TvIp, [int]$Port = 5555)
# Builds the overlay APK, installs it on the TV and grants "draw over other apps".
$ErrorActionPreference = "Stop"
$adb = "${TvIp}:$Port"
$pkg = "com.boredmancodes.tvadmute"
& "$PSScriptRoot\overlay\build.ps1"
adb -s $adb install -r "$PSScriptRoot\overlay\build\tv-admute-overlay.apk"
if ($LASTEXITCODE) { throw "adb install failed; is the TV authorised? (adb connect $adb)" }
adb -s $adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow
adb -s $adb shell am start-foreground-service -n "$pkg/.OverlayService" | Out-Null
Write-Host "Overlay installed. Test it (covers the screen for 10 seconds):"
Write-Host "  adb -s $adb shell am start-foreground-service -n $pkg/.OverlayService -a SHOW --ei secs 10"
