package com.textview.reader.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.textview.reader.R;
import com.textview.reader.ReaderActivity;

/**
 * Shows a persistent notification while reading, allowing quick return to the book.
 */
public class ReadingNotificationHelper {
    private static final String CHANNEL_ID = "reading_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final Context context;
    private final NotificationManager notificationManager;

    public ReadingNotificationHelper(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Reading Progress",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows current reading progress");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void showReading(String fileName, int progressPercent, String filePath) {
        Intent intent = new Intent(context, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_FILE_PATH, filePath);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        builder.setSmallIcon(R.drawable.ic_text_file)
                .setContentTitle(fileName)
                .setContentText(progressPercent + "% read")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, progressPercent, false);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    public void dismiss() {
        notificationManager.cancel(NOTIFICATION_ID);
    }
}
