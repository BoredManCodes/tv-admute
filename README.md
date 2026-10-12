# tv-admute

Mutes a Google TV or Android TV while the 7plus app (Australia) plays an ad break, and unmutes when the show returns.

It works along the same lines as the ad muting in [iSponsorBlockTV](https://github.com/dmunozv04/iSponsorBlockTV): a script on another machine watches what the TV app is doing and mutes the TV during ads. The difference is the signal. iSponsorBlockTV pairs with the YouTube app and reads ad state from YouTube's own remote-control API. 7plus has no such API, and Cast status and the Android media session do not change during its ads. The only place the ad shows up is the on-screen countdown (`1:58`, then bare seconds like `59` once under a minute), which is exposed through Android's accessibility tree.

## How it works

1. `uiautomator2` keeps one accessibility connection open to the TV over ADB (network, port 5555).
2. About every 0.7 seconds the script reads the 7plus screen and looks for the countdown text in the bottom-left corner.
3. Countdown present: it mutes the TV over Google Cast (`pychromecast`).
4. Countdown gone for two checks in a row: it unmutes.

Safety behaviour:
- It only unmutes if it did the muting, so a mute you set yourself stays.
- If the screen cannot be read for about 15 seconds while it holds the mute, it unmutes.
- On exit it unmutes.
- If ADB or Cast drops, it reconnects every 15 seconds.

## Requirements

- A Google TV or Android TV with the 7plus app (`com.swm.live`). Tested on one Toshiba Google TV at 1920x1080.
- The TV and the PC on the same network. Give the TV a fixed IP or a DHCP reservation.
- Windows PC (the scheduled task scripts are PowerShell; `tv_admute.py` itself should run anywhere Python and `adb` run).
- Python 3.10 or newer.
- `adb` (Android platform-tools) on PATH.

## Setup

### 1. Enable debugging on the TV

1. Settings, System, About: select the **build number** (the entry is named differently on some TVs) seven times to unlock Developer options. Menu names vary by TV.
2. Settings, System, Developer options: turn on **USB debugging**. On many TVs this also opens ADB on the network (port 5555) even though there is no Wireless debugging menu.

### 2. Authorise the PC

```powershell
adb connect 192.168.0.122:5555    # use your TV's IP
```

The first attempt prints `failed to authenticate` and `adb devices` shows `unauthorized`. A prompt appears on the TV: tick **Always allow from this computer** and choose Allow. Run the command again; `adb devices` must show `device`.

If the connection is refused, the TV does not open network ADB and this project will not work on it.

### 3. Install

```powershell
git clone https://github.com/BoredManCodes/tv-admute.git
cd tv-admute
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

### 4. Run it

```powershell
$env:TV_IP = "192.168.0.122"
.venv\Scripts\python tv_admute.py
```

On the first run the TV shows an **accessibility service** toast once. That is the persistent connection starting; it should not repeat. Start an episode and wait for an ad break. The console prints `ad on, muted` and `ad off, unmuted`.

Options (flag or environment variable):

| Flag | Env var | Default | Meaning |
|---|---|---|---|
| `--tv-ip` | `TV_IP` | none, required | TV address |
| `--adb-port` | `TV_ADB_PORT` | `5555` | ADB port on the TV |
| `--package` | `TV_PACKAGE` | `com.swm.live` | App package to watch |

### 5. Cover the picture during ads (optional)

A small Android app draws a full-screen black overlay during the ad break. It shows "We'll be right back after this short ad break", a countdown that follows the real ad countdown, and either the latest [xkcd](https://xkcd.com) comic or the quote of the day, alternating each break. 7plus keeps playing underneath, so the ad still counts down and the script can still read it.

Needs the JDK (17 tested) and the Android SDK command-line tools (build-tools and a platform, API 34 or newer). The build uses `aapt2`, `javac`, `d8` and `apksigner` directly, with no Gradle.

```powershell
.\install-overlay.ps1 -TvIp 192.168.0.122
```

This builds `overlay\build\tv-admute-overlay.apk`, installs it, grants "draw over other apps" with `appops`, and starts its service. `tv_admute.py` detects the app at startup and logs `connected, overlay on`; if it is not installed the script mutes only. Use `--no-overlay` (or `TV_OVERLAY=0`) to turn it off.

Manual test (covers the screen for 10 seconds, then hides when you send HIDE):

```powershell
adb shell am start-foreground-service -n com.boredmancodes.tvadmute/.OverlayService -a SHOW --ei secs 10
adb shell am start-foreground-service -n com.boredmancodes.tvadmute/.OverlayService -a HIDE
```

Content comes from the internet at runtime and refreshes every 3 hours: the comic from `xkcd.com/info.0.json` (licensed CC BY-NC 2.5, credit shown on screen) and the quote from the `zenquotes.io/api/today` endpoint (credit shown on screen). If both fail to load, the overlay shows only the message and countdown. The overlay window does not take focus or touch input, so the remote still controls 7plus underneath.

### 6. Start it at logon (optional)

```powershell
.\install-task.ps1 -TvIp 192.168.0.122
Start-ScheduledTask TvAdMute
```

This registers a task named `TvAdMute` that runs `run-tv-admute.ps1` through `run-hidden.vbs`, so no console window appears. Output goes to `tv-admute.log` next to the script (trimmed when it passes 1 MB). The script expects the virtual environment at `.venv` in the repo folder, and `adb` on PATH or in `%LOCALAPPDATA%\Android\Sdk\platform-tools`.

To remove it: `Unregister-ScheduledTask TvAdMute -Confirm:$false`.

## Limits

- Detection depends on the countdown layout in the 7plus app. An app update that moves or removes it breaks detection. The match is a short number or `m:ss` text in the bottom-left corner of the screen.
- The check looks for a bare one or two digit number in that corner, so any other 7plus screen that shows one there could trigger a mute. The player controls overlay (pause, seek bar) has not been tested for this.
- Detection lags by up to about a second (each screen read takes around 0.7 seconds).
- This mutes and covers the picture; it does not skip the ads.
- The overlay uses Android's overlay window type. TVs that block it (or that do not allow `appops` to grant it) will mute only.
- If the TV is off or asleep, ADB is unreachable and the script retries every 15 seconds until it returns.
- Only the app package given by `--package` is watched. Other apps show no countdown, so the script leaves the volume alone.

## Troubleshooting

| Symptom | Check |
|---|---|
| `failed to authenticate` or `unauthorized` | Accept the prompt on the TV, then `adb connect` again. |
| `connection refused` | USB debugging is off, or the TV does not open network ADB. |
| Accessibility toast keeps appearing | Something is starting `uiautomator` on every check instead of using this script. Stop other ADB tools. |
| Mutes the wrong TV | `--tv-ip` points at a different Cast device. The script picks the device matching that IP. |
| Unmutes near the end of the ad | The countdown format changed; check the pattern in `make_detector` in `tv_admute.py`. |
| Nothing happens | Watch `tv-admute.log`. `connected` should appear within about 20 seconds of starting. |

To see what the TV currently exposes (needs the venv):

```powershell
.venv\Scripts\python -c "import uiautomator2 as u2; print(u2.connect('192.168.0.122:5555').dump_hierarchy())"
```
