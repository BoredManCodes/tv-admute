"""Mute a Google TV / Android TV while 7plus shows an ad break.

7plus draws an ad countdown (m:ss, then bare seconds under a minute) in the
bottom-left corner. It only exists in the accessibility tree, so uiautomator2
keeps one accessibility connection open (one toast at startup). Muting goes
over Cast; ADB is only used to read the screen.
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
ROOT_BOUNDS = re.compile(r'bounds="\[0,0\]\[(\d+),(\d+)\]"')


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
    args = p.parse_args()
    if not args.tv_ip:
        p.error("--tv-ip or TV_IP is required")
    return args


def make_detector(package):
    countdown = re.compile(
        r'<node [^>]*text="(\d{1,2}:\d\d|\d{1,2})"[^>]*package="%s"[^>]*'
        r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(package)
    )

    def ad_on(d):
        """True/False, or None if the dump failed (keep previous state)."""
        try:
            xml = d.dump_hierarchy()
        except Exception:
            return None
        if package not in xml:
            return False
        root = ROOT_BOUNDS.search(xml)
        w, h = (int(root.group(1)), int(root.group(2))) if root else (1920, 1080)
        for _text, x1, y1, _x2, _y2 in countdown.findall(xml):
            if int(x1) < 0.21 * w and int(y1) > 0.74 * h:   # bottom-left corner
                return True
        return False

    return ad_on


def connect_cast(ip):
    found, browser = pychromecast.get_chromecasts(timeout=15, known_hosts=[ip])
    cast = next(c for c in found if c.cast_info.host == ip)
    cast.wait(timeout=15)
    return cast, browser


def run(args):
    adb = f"{args.tv_ip}:{args.adb_port}"
    ad_on = make_detector(args.package)
    subprocess.run(["adb", "connect", adb], capture_output=True, timeout=15)
    d = u2.connect(adb)
    cast, browser = connect_cast(args.tv_ip)
    log("connected")
    we_muted = False
    misses = fails = 0
    try:
        while True:
            state = ad_on(d)
            if state is None:
                fails += 1
                if we_muted and fails >= UNMUTE_AFTER_FAILS:
                    cast.set_volume_muted(False)
                    we_muted = False
                    log("screen unreadable, unmuted")
                if fails >= UNMUTE_AFTER_FAILS * 3:
                    raise RuntimeError("screen unreadable, reconnecting")
            else:
                fails = 0
                if state:
                    misses = 0
                    if not we_muted and not cast.status.volume_muted:
                        cast.set_volume_muted(True)
                        we_muted = True
                        log("ad on, muted")
                else:
                    misses += 1
                    if we_muted and misses >= UNMUTE_AFTER_MISSES:
                        cast.set_volume_muted(False)
                        we_muted = False
                        log("ad off, unmuted")
            time.sleep(POLL_S)
    finally:
        try:
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
