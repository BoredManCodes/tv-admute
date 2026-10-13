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
 * and one card per break (xkcd comic, meme, quote, dad joke, fact, cat or dog
 * picture). Cards are fetched ahead of time into a small pool. Used by both the
 * accessibility service and the adb-driven OverlayService; only the window type
 * differs.
 */
final class OverlayController {
    private static final int POOL_TARGET = 10;
    private static final long ROTATE_MS = 20000;
    private static final String[] MEME_SUBS = {"memes", "wholesomememes", "funny"};
    private static final String[] COMIC_SUBS = {"comics", "wholesomecomics"};
    // card kinds; the fetcher walks 0..KINDS-1 in order
    private static final int KINDS = 10;

    private static final class Card {
        Bitmap img;
        String text = "";
        float sp = 18;
        String credit = "";
    }

    private final Context ctx;
    private final int windowType;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private FrameLayout root;
    private TextView headline, clock, body, credit, stats;
    private ImageView image;
    private boolean shown;
    private boolean contentShown;
    private long deadline;

    private final java.util.ArrayDeque<Card> pool = new java.util.ArrayDeque<>();
    private final java.util.Random rnd = new java.util.Random();
    private int kind = new java.util.Random().nextInt(KINDS);
    private int servedXkcd;
    private long apodRetryAt;
    private volatile boolean fetching;

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!shown) return;
            populate(true);   // keeps the current card when the pool is empty
            fetch();
            ui.postDelayed(this, ROTATE_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!shown) return;
            long left = Math.max(0, (deadline - SystemClock.elapsedRealtime() + 999) / 1000);
            clock.setText(String.format("%d:%02d", left / 60, left % 60));
            headline.setText(left == 0
                    ? "Back in a moment"
                    : "We'll be right back after this short ad break");
            stats.setText(Stats.line(ctx));   // totals include the running break
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
        contentShown = false;
        populate(true);
        stats.setText(Stats.line(ctx));
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
        ui.postDelayed(rotate, ROTATE_MS);
        fetch();   // top the pool up
    }

    void sync(int secs) {
        long target = SystemClock.elapsedRealtime() + secs * 1000L;
        if (Math.abs(target - deadline) > 1500) deadline = target;
    }

    void hide() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(rotate);
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
        stats = text(15, Color.LTGRAY);
        stats.setPadding(0, dp(8), 0, 0);
        col.addView(stats);

        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Take the next card from the pool; with advance=false only fill an empty screen. */
    private void populate(boolean advance) {
        Card c;
        synchronized (pool) { c = pool.poll(); }
        if (c == null) {
            if (!advance || contentShown) return;   // keep what is on screen
            image.setVisibility(View.GONE);
            body.setText("");
            credit.setText("");
            contentShown = false;
            return;
        }
        contentShown = true;
        if (c.img != null) {
            image.setImageBitmap(c.img);
            image.setVisibility(View.VISIBLE);
        } else {
            image.setVisibility(View.GONE);
        }
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, c.sp);
        body.setText(c.text);
        credit.setText(c.credit);
    }

    /** Fill the pool in the background; safe to call often. */
    void fetch() {
        if (fetching) return;
        fetching = true;
        new Thread(() -> {
            int tries = 0;
            while (tries++ < POOL_TARGET * 2) {
                synchronized (pool) { if (pool.size() >= POOL_TARGET) break; }
                Card c = null;
                try { c = build(kind++ % KINDS); } catch (Exception ignored) { }
                if (c != null) synchronized (pool) { pool.add(c); }
                // content can arrive mid-break after a cold start; fill it in
                ui.post(() -> { if (shown && !contentShown) populate(false); });
            }
            fetching = false;
        }).start();
    }

    private Card build(int k) throws Exception {
        Card c = new Card();
        switch (k) {
            case 0: {   // xkcd of the day: the newest strip, served once per new strip
                JSONObject j = new JSONObject(str(get("https://xkcd.com/info.0.json", "application/json")));
                if (j.getInt("num") == servedXkcd) return null;
                c.img = bitmap(get(j.getString("img"), "image/*"), 1800);
                if (c.img != null) servedXkcd = j.getInt("num");
                c.text = j.getString("safe_title") + "\n" + j.getString("alt");
                c.sp = 14;
                c.credit = "xkcd.com, CC BY-NC 2.5";
                return c.img == null ? null : c;
            }
            case 1: return reddit(MEME_SUBS, 4);    // meme
            case 5: case 9: return reddit(COMIC_SUBS, 5);   // comic; use the largest preview
            case 8: {   // NASA astronomy picture, random from the archive
                if (SystemClock.elapsedRealtime() < apodRetryAt) return null;
                String key = ctx.getSharedPreferences("config", Context.MODE_PRIVATE).getString("nasa_key", "DEMO_KEY");
                JSONArray a = new JSONArray(str(get("https://api.nasa.gov/planetary/apod?api_key=" + key + "&count=1", "application/json")));
                JSONObject j = a.getJSONObject(0);
                if (j.optString("url").contains("nasa-logo")) {
                    // the API has served this placeholder for every date; try again in an hour
                    apodRetryAt = SystemClock.elapsedRealtime() + 60L * 60 * 1000;
                    return null;
                }
                if (!"image".equals(j.optString("media_type"))) return null;
                c.img = bitmap(get(j.getString("url"), "image/*"), 1600);
                String ex = j.optString("explanation");
                if (ex.length() > 220) ex = ex.substring(0, ex.lastIndexOf(' ', 220)) + "…";
                c.text = j.optString("title") + "\n" + ex;
                c.sp = 14;
                c.credit = "NASA APOD" + (j.has("copyright") ? ", © " + j.getString("copyright").trim().replace("\n", " ") : "");
                return c.img == null ? null : c;
            }
            case 2: {   // quote
                JSONObject q = new JSONArray(str(get("https://zenquotes.io/api/random", "application/json"))).getJSONObject(0);
                c.text = "“" + q.getString("q") + "”\n\n— " + q.getString("a");
                c.sp = 26;
                c.credit = "Quotes by ZenQuotes.io";
                return c;
            }
            case 3: {   // cat
                c.img = bitmap(get("https://cataas.com/cat", "image/*"), 1400);
                c.credit = "cataas.com";
                return c.img == null ? null : c;
            }
            case 4: {   // dad joke
                JSONObject j = new JSONObject(str(get("https://icanhazdadjoke.com/", "application/json")));
                c.text = j.getString("joke");
                c.sp = 28;
                c.credit = "icanhazdadjoke.com";
                return c;
            }
            case 6: {   // fact
                JSONObject j = new JSONObject(str(get("https://uselessfacts.jsph.pl/api/v2/facts/random?language=en", "application/json")));
                c.text = "Did you know?\n\n" + j.getString("text");
                c.sp = 26;
                c.credit = "uselessfacts.jsph.pl";
                return c;
            }
            default: {  // dog
                JSONObject j = new JSONObject(str(get("https://dog.ceo/api/breeds/image/random", "application/json")));
                c.img = bitmap(get(j.getString("message"), "image/*"), 1400);
                c.credit = "dog.ceo";
                return c.img == null ? null : c;
            }
        }
    }

    /** A post from a random one of the given subreddits via meme-api.com; skips NSFW and spoiler posts. */
    private Card reddit(String[] subs, int previewIdx) throws Exception {
        String sub = subs[rnd.nextInt(subs.length)];
        JSONObject j = new JSONObject(str(get("https://meme-api.com/gimme/" + sub, "application/json")));
        if (j.optBoolean("nsfw") || j.optBoolean("spoiler")) return null;
        JSONArray prev = j.optJSONArray("preview");
        String url = prev != null && prev.length() > 0
                ? prev.getString(Math.min(previewIdx, prev.length() - 1)) : j.getString("url");
        Card c = new Card();
        c.img = bitmap(get(url, "image/*"), 1600);
        c.text = j.optString("title");
        c.sp = 16;
        c.credit = "r/" + j.optString("subreddit", sub) + " via meme-api.com";
        return c.img == null ? null : c;
    }

    private static String str(byte[] b) throws Exception { return new String(b, "UTF-8"); }

    private static Bitmap bitmap(byte[] raw, int maxWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(1, o.outWidth / maxWidth);
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    private static byte[] get(String url, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "tv-admute (github.com/BoredManCodes/tv-admute)");
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
