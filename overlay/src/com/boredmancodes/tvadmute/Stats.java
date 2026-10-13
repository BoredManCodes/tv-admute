package com.boredmancodes.tvadmute;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Counts of ad breaks blocked: lifetime totals on disk, session totals since the service started. */
final class Stats {
    private static SharedPreferences prefs;
    private static int sessionBreaks;
    private static long sessionMs, startedAt;
    private static final long sessionSince = System.currentTimeMillis();

    private Stats() { }

    private static SharedPreferences p(Context c) {
        if (prefs == null) prefs = c.getApplicationContext().getSharedPreferences("stats", Context.MODE_PRIVATE);
        return prefs;
    }

    static synchronized void breakStarted(Context c) {
        startedAt = SystemClock.elapsedRealtime();
        sessionBreaks++;
        p(c).edit().putInt("breaks", p(c).getInt("breaks", 0) + 1).apply();
    }

    static synchronized void breakEnded(Context c) {
        if (startedAt == 0) return;
        long ms = SystemClock.elapsedRealtime() - startedAt;
        startedAt = 0;
        sessionMs += ms;
        p(c).edit().putLong("ms", p(c).getLong("ms", 0) + ms).apply();
    }

    /** Adds breaks counted from old logs to the lifetime totals. */
    static synchronized void backfill(Context c, int breaks, long ms) {
        p(c).edit().putInt("breaks", p(c).getInt("breaks", 0) + breaks)
                .putLong("ms", p(c).getLong("ms", 0) + ms).apply();
    }

    static synchronized int totalBreaks(Context c) { return p(c).getInt("breaks", 0); }

    static synchronized long totalMs(Context c) {
        return p(c).getLong("ms", 0) + running();
    }

    static synchronized int sessionBreaks() { return sessionBreaks; }

    static synchronized long sessionMs() { return sessionMs + running(); }

    static long sessionSince() { return sessionSince; }

    private static long running() {
        return startedAt == 0 ? 0 : SystemClock.elapsedRealtime() - startedAt;
    }

    static String duration(long ms) {
        long s = ms / 1000;
        if (s >= 3600) return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        if (s >= 60) return (s / 60) + "m " + (s % 60) + "s";
        return s + "s";
    }

    /** One line for the bottom of the cover. */
    static String line(Context c) {
        return "This session: " + sessionBreaks() + " breaks, " + duration(sessionMs())
                + "  |  All time: " + totalBreaks(c) + " breaks, " + duration(totalMs(c));
    }
}
