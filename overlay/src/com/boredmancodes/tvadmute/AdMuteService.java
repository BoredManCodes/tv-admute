package com.boredmancodes.tvadmute;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mutes the TV and covers the picture while 7plus shows an ad break.
 *
 * 7plus draws an ad countdown (m:ss, then bare seconds under a minute) in the
 * bottom-left corner; it only exists in the accessibility tree. This service
 * reads it on the device, so no PC and no UiAutomation session is involved
 * (UiAutomation switches off every other accessibility service, which killed
 * remotes that depend on one).
 *
 * Debug broadcast (adb shell am broadcast):
 *   -a com.boredmancodes.tvadmute.DEBUG --es cmd show --ei secs 10
 *   -a com.boredmancodes.tvadmute.DEBUG --es cmd hide
 */
public class AdMuteService extends AccessibilityService {
    private static final String APP = "com.swm.live";
    private static final String DEBUG_ACTION = "com.boredmancodes.tvadmute.DEBUG";
    private static final Pattern COUNTDOWN = Pattern.compile("^(?:(\\d{1,2}):(\\d{2})|(\\d{1,2}))$");
    private static final long POLL_MS = 700;
    private static final long CHECK_GAP_MS = 400;
    private static final int MISSES_TO_END = 3;
    private static final long SYNC_MS = 4000;
    private static final int NO_AD = -1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private OverlayController overlay;
    private AudioManager audio;
    private boolean inAd, weMuted, debugHold, inPlayer;
    private int misses;
    private long lastCheck, lastSync;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!inAd) return;
            check();
            if (inAd) h.postDelayed(this, POLL_MS);
        }
    };

    private final BroadcastReceiver debug = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String cmd = i.getStringExtra("cmd");
            if ("show".equals(cmd)) {
                debugHold = true;
                startAd(i.getIntExtra("secs", 10));
            } else if ("nasakey".equals(cmd)) {
                getSharedPreferences("config", MODE_PRIVATE).edit()
                        .putString("nasa_key", i.getStringExtra("key")).apply();
            } else if ("backfill".equals(cmd)) {
                Stats.backfill(AdMuteService.this, i.getIntExtra("n", 0), i.getIntExtra("secs", 0) * 1000L);
            } else if ("hide".equals(cmd)) {
                debugHold = false;
                endAd();
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        overlay = new OverlayController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        registerReceiver(debug, new IntentFilter(DEBUG_ACTION));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!APP.contentEquals(e.getPackageName() == null ? "" : e.getPackageName())) return;
        CharSequence cls = e.getClassName();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && cls != null
                && cls.toString().startsWith("au.com.seven")) {
            inPlayer = cls.toString().endsWith(".VideoActivity");
        }
        if (inAd) return;
        if (SystemClock.elapsedRealtime() - lastCheck < CHECK_GAP_MS) return;
        check();
    }

    @Override public void onInterrupt() { endAd(); }

    @Override public boolean onUnbind(Intent intent) {
        endAd();
        try { unregisterReceiver(debug); } catch (Exception ignored) { }
        return super.onUnbind(intent);
    }

    private void check() {
        lastCheck = SystemClock.elapsedRealtime();
        if (debugHold) return;
        int secs = readCountdown();
        if (secs != NO_AD) {
            misses = 0;
            if (!inAd) startAd(secs);
            else if (lastCheck - lastSync >= SYNC_MS) {
                overlay.sync(secs);
                lastSync = lastCheck;
            }
        } else if (inAd && ++misses >= MISSES_TO_END) {
            endAd();
        }
    }

    private void startAd(int secs) {
        if (inAd) { overlay.sync(secs); return; }
        inAd = true;
        misses = 0;
        if (!audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            weMuted = true;
        }
        if (!debugHold) Stats.breakStarted(this);
        overlay.show(secs);
        lastSync = SystemClock.elapsedRealtime();
        h.removeCallbacks(poll);
        h.postDelayed(poll, POLL_MS);
    }

    private void endAd() {
        h.removeCallbacks(poll);
        if (!inAd) return;
        inAd = false;
        if (weMuted) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            weMuted = false;
        }
        overlay.hide();
        Stats.breakEnded(this);
    }

    /** Seconds left on the countdown in the bottom-left corner, or NO_AD. */
    private int readCountdown() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return NO_AD;
        try {
            if (root.getPackageName() == null || !APP.contentEquals(root.getPackageName())) return NO_AD;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            Rect r = new Rect();
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo n = queue.poll();
                CharSequence t = n.getText();
                if (t != null) {
                    Matcher m = COUNTDOWN.matcher(t);
                    // A bare number also shows up in menus (list positions), so outside the player only m:ss counts.
                    if (m.matches() && (inPlayer || inAd || m.group(3) == null)) {
                        n.getBoundsInScreen(r);
                        if (r.left < 0.21 * dm.widthPixels && r.top > 0.74 * dm.heightPixels) {
                            return m.group(3) != null
                                    ? Integer.parseInt(m.group(3))
                                    : Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                        }
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) queue.add(c);
                }
            }
        } catch (RuntimeException e) {
            return NO_AD;   // window changed under us; the next check retries
        }
        return NO_AD;
    }
}
