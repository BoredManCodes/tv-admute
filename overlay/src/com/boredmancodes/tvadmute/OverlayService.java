package com.boredmancodes.tvadmute;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.view.WindowManager;

/**
 * Legacy adb-driven overlay, used by the PC script (tv_admute.py):
 *   am start-foreground-service -n com.boredmancodes.tvadmute/.OverlayService -a SHOW --ei secs 118
 * Actions: SHOW (secs), SYNC (secs), HIDE. Needs the draw-over-other-apps permission.
 * The on-device AdMuteService replaces this and needs nothing from the PC.
 */
public class OverlayService extends Service {
    private static final String CHANNEL = "tv-admute";
    private OverlayController overlay;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "tv-admute", NotificationManager.IMPORTANCE_MIN));
        overlay = new OverlayController(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("tv-admute").build());
        String action = intent == null ? null : intent.getAction();
        int secs = intent == null ? 0 : intent.getIntExtra("secs", 0);
        if ("SHOW".equals(action)) overlay.show(secs);
        else if ("SYNC".equals(action)) overlay.sync(secs);
        else if ("HIDE".equals(action)) overlay.hide();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        overlay.hide();
        super.onDestroy();
    }
}
