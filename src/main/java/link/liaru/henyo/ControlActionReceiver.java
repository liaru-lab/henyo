package link.liaru.henyo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, non-exported on-device action; remote clients cannot resume control. */
public final class ControlActionReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "control";
    private static final int ID = 100;

    @Override public void onReceive(Context context, Intent intent) {
        // Notification only offers stop. Resume requires an explicit app/panel action.
        HenyoAccessibilityService.setControlStopped(context, true);
    }

    static void updateNotification(Context context, boolean stopped) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Control status",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(stopped ? "Control stopped" : "Henyo control available")
                .setContentText(stopped ? "Open Henyo to resume control." : "Stop control to take over.")
                .setContentIntent(open).setOngoing(true);
        if (!stopped) {
            PendingIntent stop = PendingIntent.getBroadcast(context, 0,
                    new Intent(context, ControlActionReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "Stop control", stop).build());
        }
        try { manager.notify(ID, builder.build()); } catch (SecurityException ignored) {
            // The on-screen and app controls remain available if notifications are disabled.
        }
    }

    static void cancel(Context context) {
        context.getSystemService(NotificationManager.class).cancel(ID);
    }
}
