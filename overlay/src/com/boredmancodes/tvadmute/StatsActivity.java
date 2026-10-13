package com.boredmancodes.tvadmute;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/** Shows how many ad breaks were blocked and for how long. Back closes it. */
public class StatsActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView session, total, since;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            session.setText(Stats.sessionBreaks() + " breaks blocked\n" + Stats.duration(Stats.sessionMs()) + " of ads");
            total.setText(Stats.totalBreaks(StatsActivity.this) + " breaks blocked\n"
                    + Stats.duration(Stats.totalMs(StatsActivity.this)) + " of ads");
            ui.postDelayed(this, 1000);
        }
    };

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 16, 0, 16);
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Color.BLACK);

        col.addView(text("Ad break stats", 32, Color.WHITE));
        col.addView(text("This session", 18, Color.GRAY));
        session = text("", 36, Color.WHITE);
        session.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        col.addView(session);
        since = text("since " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(Stats.sessionSince()))
                + " (when the service last started)", 14, Color.GRAY);
        col.addView(since);
        col.addView(text("All time", 18, Color.GRAY));
        total = text("", 36, Color.WHITE);
        total.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        col.addView(total);
        setContentView(col);
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }

    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); }
}
