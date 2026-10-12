"""Mute a Google TV / Android TV while 7plus shows an ad break.

7plus draws an ad countdown (m:ss, then bare seconds under a minute) in the
bottom-left corner. It only exists in the accessibility tree, so uiautomator2
keeps one accessibility connection open (one toast at startup). Muting goes
over Cast; ADB reads the screen and drives the optional black overlay app
(see overlay/) that covers the picture during the break.
"""
import argparse
import os
import re
import subprocess
import sys
import time

import pychromecast
import uiautomator2 as u2

POLL_S = 0.7
UNMUTE_AFTER_MISSES = 2   # consecutive checks with no countdown
UNMUTE_AFTER_FAILS = 20   # consecutive failed dumps; don't stay muted blind
SYNC_EVERY_S = 4          # re-send the remaining seconds to the overlay
ROOT_BOUNDS = re.compile(r'bounds="\[0,0\]\[(\d+),(\d+)\]"')
OVERLAY_PKG = "com.boredmancodes.tvadmute"
OVERLAY_SVC = f"{OVERLAY_PKG}/.OverlayService"
NO_AD = -1


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def parse_args():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--tv-ip", default=os.environ.get("TV_IP"),
                   help="TV address (or env TV_IP)")
    p.add_argument("--adb-port", type=int,
                   default=int(os.environ.get("TV_ADB_PORT", "5555")))
    p.add_argument("--package", default=os.environ.get("TV_PACKAGE", "com.swm.live"),
                   help="app package to watch (default: 7plus)")
    p.add_argument("--no-overlay", action="store_true",
                   default=os.environ.get("TV_OVERLAY") == "0",
                   help="mute only, do not cover the picture")
    args = p.parse_args()
    if not args.tv_ip:
        p.error("--tv-ip or TV_IP is required")
    return args


def to_seconds(text):
    if ":" in text:
        m, s = text.split(":")
        return int(m) * 60 + int(s)
    return int(text)


def make_detector(package):
    countdown = re.compile(
        r'<node [^>]*text="(\d{1,2}:\d\d|\d{1,2})"[^>]*package="%s"[^>]*'
        r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(package)
    )

    def read_ad(d):
        """Seconds left in the ad, NO_AD, or None if the dump failed."""
        try:
            xml = d.dump_hierarchy()
        except Exception:
            return None
        if package not in xml:
            return NO_AD
        root = ROOT_BOUNDS.search(xml)
        w, h = (int(root.group(1)), int(root.group(2))) if root else (1920, 1080)
        for text, x1, y1, _x2, _y2 in countdown.findall(xml):
            if int(x1) < 0.21 * w and int(y1) > 0.74 * h:   # bottom-left corner
                return to_seconds(text)
        return NO_AD

    return read_ad


class Overlay:
    """Drives the overlay app over ADB. Fire and forget; failures are ignored."""

    def __init__(self, adb, enabled):
        self.adb = adb
        self.enabled = enabled

    def _cmd(self, action, secs=None):
        if not self.enabled:
            return
        cmd = ["adb", "-s", self.adb, "shell", "am", "start-foreground-service",
               "-n", OVERLAY_SVC, "-a", action]
        if secs is not None:
            cmd += ["--ei", "secs", str(secs)]
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def show(self, secs): self._cmd("SHOW", secs)
    def sync(self, secs): self._cmd("SYNC", secs)
    def hide(self): self._cmd("HIDE")


def overlay_installed(adb):
    r = subprocess.run(["adb", "-s", adb, "shell", "pm", "list", "packages", OVERLAY_PKG],
                       capture_output=True, text=True, timeout=15)
    return OVERLAY_PKG in r.stdout


def connect_cast(ip):
    found, browser = pychromecast.get_chromecasts(timeout=15, known_hosts=[ip])
    cast = next(c for c in found if c.cast_info.host == ip)
    cast.wait(timeout=15)
    return cast, browser


def run(args):
    adb = f"{args.tv_ip}:{args.adb_port}"
    read_ad = make_detector(args.package)
    subprocess.run(["adb", "connect", adb], capture_output=True, timeout=15)
    d = u2.connect(adb)
    cast, browser = connect_cast(args.tv_ip)
    use_overlay = not args.no_overlay
    if use_overlay and not overlay_installed(adb):
        log("overlay app not installed, muting only (see README)")
        use_overlay = False
    overlay = Overlay(adb, use_overlay)
    log("connected" + (", overlay on" if use_overlay else ""))

    in_ad = we_muted = False
    misses = fails = 0
    last_sync = 0.0
    try:
        while True:
            secs = read_ad(d)
            if secs is None:
                fails += 1
                if in_ad and fails >= UNMUTE_AFTER_FAILS:
                    if we_muted:
                        cast.set_volume_muted(False)
                        we_muted = False
                    overlay.hide()
                    in_ad = False
                    log("screen unreadable, unmuted")
                if fails >= UNMUTE_AFTER_FAILS * 3:
                    raise RuntimeError("screen unreadable, reconnecting")
            elif secs != NO_AD:
                fails = misses = 0
                if not in_ad:
                    in_ad = True
                    if not cast.status.volume_muted:
                        cast.set_volume_muted(True)
                        we_muted = True
                    overlay.show(secs)
                    last_sync = time.time()
                    log(f"ad on ({secs}s), muted" if we_muted else f"ad on ({secs}s)")
                elif time.time() - last_sync >= SYNC_EVERY_S:
                    overlay.sync(secs)
                    last_sync = time.time()
            else:
                fails = 0
                misses += 1
                if in_ad and misses >= UNMUTE_AFTER_MISSES:
                    if we_muted:
                        cast.set_volume_muted(False)
                        we_muted = False
                    overlay.hide()
                    in_ad = False
                    log("ad off, unmuted")
            time.sleep(POLL_S)
    finally:
        try:
            if in_ad:
                overlay.hide()
            if we_muted:
                cast.set_volume_muted(False)
                log("exit, unmuted")
        finally:
            browser.stop_discovery()


if __name__ == "__main__":
    args = parse_args()
    while True:
        try:
            run(args)
        except KeyboardInterrupt:
            sys.exit(0)
        except Exception as e:
            log(f"error: {e!r}, retry in 15s")
            time.sleep(15)
