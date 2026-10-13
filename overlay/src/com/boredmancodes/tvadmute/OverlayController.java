package com.boredmancodes.tvadmute;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The black full-screen cover shown during an ad break: a message, a countdown,
 * and either the latest xkcd comic (CC BY-NC 2.5) or the ZenQuotes quote of the
 * day, alternating. Used by both the accessibility service and the adb-driven
 * OverlayService; only the window type differs.
 */
final class OverlayController {
    private static final long REFRESH_MS = 3L * 60 * 60 * 1000;

    private final Context ctx;
    private final int windowType;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private FrameLayout root;
    private TextView headline, clock, body, credit;
    private ImageView image;
    private boolean shown;
    private boolean contentShown;
    private long deadline;
    private int showCount;

    private volatile Bitmap comic;
    private volatile String comicTitle, comicAlt, quote, quoteBy;
    private volatile long fetchedAt;
    private volatile boolean fetching;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!shown) return;
            long left = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
            clock.setText(String.format("%d:%02d", left / 60, left % 60));
            headline.setText(left == 0
                    ? "Back in a moment"
                    : "We'll be right back after this short ad break");
            ui.postDelayed(this, 250);
        }
    };

    OverlayController(Context ctx, int windowType) {
        this.ctx = ctx;
        this.windowType = windowType;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        buildView();
        fetch();
    }

    boolean isShown() { return shown; }

    void show(int secs) {
        if (shown) { sync(secs); return; }
        populate(true);
        deadline = SystemClock.elapsedRealtime() + secs * 1000L;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        wm.addView(root, lp);
        shown = true;
        ui.post(tick);
        boolean stale = SystemClock.elapsedRealtime() - fetchedAt > REFRESH_MS;
        if ((comic == null && quote == null) || stale) fetch();
    }

    void sync(int secs) {
        long target = SystemClock.elapsedRealtime() + secs * 1000L;
        if (Math.abs(target - deadline) > 1500) deadline = target;
    }

    void hide() {
        ui.removeCallbacks(tick);
        if (shown) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            shown = false;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void buildView() {
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);
        // keep our nodes out of the accessibility tree the detector reads
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(48), dp(32), dp(48), dp(24));

        headline = text(22, Color.LTGRAY);
        clock = text(64, Color.WHITE);
        clock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.WHITE);
        image.setPadding(dp(8), dp(8), dp(8), dp(8));

        body = text(18, Color.LTGRAY);
        credit = text(10, Color.GRAY);

        col.addView(headline);
        col.addView(clock);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        imgLp.topMargin = dp(16);
        col.addView(image, imgLp);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(12);
        col.addView(body, bodyLp);
        col.addView(credit);

        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Alternate comic and quote; fall back to whichever one loaded. */
    private void populate(boolean advance) {
        if (advance) showCount++;
        boolean wantComic = showCount % 2 == 1;
        if (wantComic && comic == null) wantComic = false;
        if (!wantComic && quote == null && comic != null) wantComic = true;
        contentShown = wantComic || quote != null;
        if (wantComic) {
            image.setImageBitmap(comic);
            image.setVisibility(View.VISIBLE);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            body.setText(comicTitle + "\n" + comicAlt);
            credit.setText("xkcd.com, CC BY-NC 2.5");
        } else if (quote != null) {
            image.setVisibility(View.GONE);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            body.setText("“" + quote + "”\n\n— " + quoteBy);
            credit.setText("Quotes by ZenQuotes.io");
        } else {
            image.setVisibility(View.GONE);
            body.setText("");
            credit.setText("");
        }
    }

    void fetch() {
        if (fetching) return;
        fetching = true;
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(new String(get("https://xkcd.com/info.0.json"), "UTF-8"));
                byte[] raw = get(j.getString("img"));
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = Math.max(1, o.outWidth / 2000);
                Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                comicTitle = j.getString("safe_title");
                comicAlt = j.getString("alt");
                comic = b;
            } catch (Exception ignored) { }
            try {
                JSONArray a = new JSONArray(new String(get("https://zenquotes.io/api/today"), "UTF-8"));
                JSONObject q = a.getJSONObject(0);
                quote = q.getString("q");
                quoteBy = q.getString("a");
            } catch (Exception ignored) { }
            if (comic != null || quote != null) fetchedAt = SystemClock.elapsedRealtime();
            fetching = false;
            // content can arrive mid-break after a cold start; fill it in
            ui.post(() -> { if (shown && !contentShown) populate(false); });
        }).start();
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
