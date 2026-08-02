package org.thoughtcrime.securesms.gcm;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.signal.core.util.PendingIntentFlags;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.MainActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.notifications.NotificationIds;

/**
 * AJ fork: receives the wake signal relayed by the paired device's companion app
 * (fr.smarquis.fcm) when the peer sent a "hii" message (see MessageSender.maybeFirePingIntent).
 * Deliberately exported with no permission gate - worst case if some other app fires this,
 * we show a harmless "Ping received" notification and do one extra websocket drain.
 *
 * Two things happen, in order:
 * 1. A lightweight "Ping received" notification, sourced from Molly itself (not the
 *    companion app) - the companion app should NOT also show its own notification for
 *    this signal type, only for a plain "hi" ping.
 * 2. FcmFetchManager does the same short-lived foreground-service wake-and-drain that a
 *    real high-priority FCM push would trigger, so any pending messages actually get
 *    fetched instead of waiting on the next natural connection.
 */
public class WakeForMessagesReceiver extends BroadcastReceiver {

  private static final String TAG = Log.tag(WakeForMessagesReceiver.class);

  public static final String ACTION_WAKE_FOR_MESSAGES = "com.aj.signal.ACTION_WAKE_FOR_MESSAGES";

  @Override
  public void onReceive(@NonNull Context context, @NonNull Intent intent) {
    if (!ACTION_WAKE_FOR_MESSAGES.equals(intent.getAction())) {
      return;
    }

    Log.i(TAG, "Wake signal received - showing ping notification and checking for messages.");

    postPingReceivedNotification(context);

    FcmFetchManager.startForegroundService(context);
    FcmFetchManager.enqueueFetch(context, true);
  }

  private static void postPingReceivedNotification(@NonNull Context context) {
    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

    if (notificationManager.getNotificationChannel(NotificationChannels.ADDITIONAL_MESSAGE_NOTIFICATIONS) == null) {
      Log.w(TAG, "Notification channel for ping-received does not exist.");
      return;
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Missing permission to post notifications.");
      return;
    }

    Notification notification = new NotificationCompat.Builder(context, NotificationChannels.ADDITIONAL_MESSAGE_NOTIFICATIONS)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(ContextCompat.getColor(context, R.color.core_ultramarine))
        .setContentTitle(context.getString(R.string.WakeForMessagesReceiver__ping_received))
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setContentIntent(PendingIntent.getActivity(context, 0, MainActivity.clearTop(context), PendingIntentFlags.mutable()))
        .setVibrate(new long[]{0})
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .build();

    notificationManager.notify(NotificationIds.PING_RECEIVED, notification);
  }
}
