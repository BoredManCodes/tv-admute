# tv-admute

Mutes a Google TV or Android TV and covers the picture while the 7plus app (Australia) plays an ad break. It unmutes and clears the cover when the show returns.

It works along the same lines as the ad muting in [iSponsorBlockTV](https://github.com/dmunozv04/iSponsorBlockTV): something watches what the TV app is doing and mutes the TV during ads. The difference is the signal. iSponsorBlockTV pairs with the YouTube app and reads ad state from YouTube's own remote-control API. 7plus has no such API, and Cast status and the Android media session do not change during its ads. The only place the ad shows up is the on-screen countdown (`1:58`, then bare seconds like `59` once under a minute), which is exposed through Android's accessibility tree.

Everything runs on the TV. The PC is only used once, to install the app.

## How it works

1. A small Android app registers an accessibility service that only listens to `com.swm.live`.
2. It reads the 7plus screen and looks for the countdown text in the bottom-left corner. A countdown in `m:ss` form starts an ad break. Bare seconds also start one, but only while the player screen (`VideoActivity`) is in front, because menus show bare numbers too.
3. Countdown found: it mutes the media volume and draws a full-screen black cover. The cover shows "We'll be right back after this short ad break", a countdown that follows the real one, a card that changes every 20 seconds, and a stats line. 7plus keeps playing underneath.
4. Countdown gone for three checks in a row: it unmutes and removes the cover.

Safety behaviour:
- It only unmutes if it did the muting, so a mute you set yourself stays.
- The cover window takes no focus and no touch input, so the remote still controls 7plus.
- Turning the service off or leaving the app clears the cover and unmutes.

The service does not use UiAutomation (the thing `uiautomator` and `uiautomator2` use). UiAutomation switches off every other accessibility service while it is connected, which stops remotes that depend on one, such as a launcher's key filter.

## Requirements

- A Google TV or Android TV (Android 8 or newer) with the 7plus app (`com.swm.live`). Tested on a Toshiba Google TV (Android 11) and a Chromecast with Google TV (Android 14), both 1920x1080.
- A Windows PC on the same network to build and install (the scripts are PowerShell). `adb` (Android platform-tools) on PATH.
- JDK 17 and the Android SDK command-line tools (build-tools and a platform, API 34 or newer). The build calls `aapt2`, `javac`, `d8` and `apksigner` directly, with no Gradle.

## Setup

### 1. Enable debugging on the TV

Settings, System, About: select the **build number** seven times to unlock Developer options. Menu names vary by TV.

- **TVs with USB debugging only** (the Toshiba): turn on **USB debugging** in Developer options. This often opens ADB on the network at port 5555.
- **Devices with Wireless debugging** (Chromecast with Google TV on Android 14): turn on **Wireless debugging**. Pair once with `adb pair <ip>:<pairing port>` and the code shown on the TV, then `adb connect <ip>:<port>` using the port on the main Wireless debugging screen. That port changes after a reboot or when Wireless debugging is toggled, so the install may need the new port.

### 2. Authorise the PC

```powershell
adb connect 192.168.0.122:5555    # use your TV's IP and port
```

The first attempt prints `failed to authenticate` and `adb devices` shows `unauthorized`. A prompt appears on the TV: tick **Always allow from this computer** and choose Allow. Run the command again; `adb devices` must show `device`.

### 3. Build, install and enable

```powershell
git clone https://github.com/BoredManCodes/tv-admute.git
cd tv-admute
.\install-overlay.ps1 -TvIp 192.168.0.122            # add -Port 43599 for Wireless debugging
```

This builds `overlay\build\tv-admute-overlay.apk`, installs it, and adds its accessibility service to the TV's enabled services next to any that are already there. Use `-NoBuild` to reinstall the last build.

Once installed, the PC is no longer needed. Repeat for each TV.

### 4. Test

```powershell
adb shell am broadcast -a com.boredmancodes.tvadmute.DEBUG --es cmd show --ei secs 10
adb shell am broadcast -a com.boredmancodes.tvadmute.DEBUG --es cmd hide
```

The first covers the screen and mutes. Then start an episode and wait for an ad break.

## Cards on the cover

Cards are fetched ahead of time into a pool of 10 and one is shown every 20 seconds during a break. Each card shows its source on screen. If the next card has not loaded, the current one stays; if nothing loaded, the cover shows only the message and countdown.

| Card | Source |
|---|---|
| xkcd of the day (newest strip, shown once per new strip) | `xkcd.com/info.0.json`, CC BY-NC 2.5 |
| Comics | r/comics and r/wholesomecomics via `meme-api.com` |
| Memes | r/memes, r/wholesomememes and r/funny via `meme-api.com` |
| Astronomy picture | NASA APOD (`api.nasa.gov`) |
| Quote | `zenquotes.io` |
| Dad joke | `icanhazdadjoke.com` |
| Random fact | `uselessfacts.jsph.pl` |
| Cat / dog photo | `cataas.com`, `dog.ceo` |

NSFW and spoiler Reddit posts are skipped, but Reddit content is not vetted and belongs to its authors. To change the mix, edit `MEME_SUBS`, `COMIC_SUBS` and `build()` in `OverlayController.java`.

NASA APOD uses the shared `DEMO_KEY` (about 30 requests an hour per IP) unless you set your own free key from `api.nasa.gov`. The key lives on the TV, not in the repo:

```powershell
adb shell am broadcast -a com.boredmancodes.tvadmute.DEBUG --es cmd nasakey --es key YOUR_KEY
```

## Stats

The cover shows a line at the bottom with this session and all-time counts of breaks blocked and time blocked. It counts up while a break runs. The "Ad break stats" entry in the TV's app list shows the same numbers. Totals are saved on each TV, and each TV counts separately. "Session" means since the service last started (a reboot or reinstall). Uninstalling the app clears the totals.

## Limits

- Detection depends on the countdown layout in the 7plus app. An app update that moves or removes it breaks detection. The match is `m:ss` or a short number in the bottom-left corner of the screen.
- The service learns which screen is in front from window-change events. After it is installed or restarted, a break that starts in bare seconds is only caught once you have opened a video from the browse screen. An `m:ss` break is always caught.
- The player controls overlay (pause, seek bar) has not been tested for false triggers.
- Detection lags by up to about a second.
- This mutes and covers the picture; it does not skip the ads.
- It mutes the media stream on the TV. A soundbar or receiver that has its own mute is not affected by that.

## Troubleshooting

| Symptom | Check |
|---|---|
| `failed to authenticate` or `unauthorized` | Accept the prompt on the TV, then `adb connect` again. |
| `connection refused` | Debugging is off, or the Wireless debugging port changed. |
| Nothing happens | Settings, Accessibility: the service must be on. Run the debug `show` command above to check the cover and mute. |
| Cover shows with no ad | Bare numbers on a non-player screen; report the screen and what number is in the bottom-left. |
| Remote stops working | Another tool is using UiAutomation (`uiautomator` or `uiautomator2` over ADB). Stop it. |
| Stuck muted | `adb shell cmd media_session volume --stream 3 --set <level>` or turn the service off. |
