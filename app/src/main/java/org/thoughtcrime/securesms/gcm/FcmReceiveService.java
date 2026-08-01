package org.thoughtcrime.securesms.gcm;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobs.FcmRefreshJob;
import org.thoughtcrime.securesms.jobs.SubmitRateLimitPushChallengeJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.registration.fcm.PushChallengeRequest;
import org.thoughtcrime.securesms.service.KeyCachingService;
import org.thoughtcrime.securesms.util.NetworkUtil;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.Locale;

public class FcmReceiveService extends FirebaseMessagingService {

  private static final String TAG = Log.tag(FcmReceiveService.class);

  // AJ fork diagnostic: this service is exported=true in the manifest (upstream Molly has it
  // false), meaning any app on the device - not just the Firebase SDK internally - can invoke
  // it directly. Logging every raw onStartCommand() call (before Firebase's own parsing/
  // dispatch to onMessageReceived) catches calls that wouldn't otherwise show up, since a
  // malformed/non-Firebase-shaped Intent can start this service without ever reaching
  // onMessageReceived(). No adb/dumpsys needed - this lands in the same debug log export
  // already in use.
  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    if (intent == null) {
      Log.i(TAG, "onStartCommand() called with a null Intent (flags=" + flags + ", startId=" + startId + ")");
    } else {
      Log.i(TAG, "onStartCommand() action=" + intent.getAction()
          + " package=" + intent.getPackage()
          + " component=" + intent.getComponent()
          + " extras=" + (intent.getExtras() != null ? intent.getExtras().keySet() : "none")
          + " flags=" + flags + " startId=" + startId);
    }
    return super.onStartCommand(intent, flags, startId);
  }

  @Override
  public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
    if (KeyCachingService.isLocked()) {
      if (remoteMessage.getPriority() == RemoteMessage.PRIORITY_HIGH &&
          TextSecurePreferences.isPassphraseLockNotificationsEnabled(this)) {
        Log.d(TAG, "New urgent message received while app is locked.");
        FcmFetchManager.postMayHaveMessagesNotification(this);
      }
      return;
    }

    Log.i(TAG, String.format(Locale.US,
                             "onMessageReceived() ID: %s, Delay: %d (Server offset: %d), Priority: %d, Original Priority: %d, Network: %s",
                             remoteMessage.getMessageId(),
                             (System.currentTimeMillis() - remoteMessage.getSentTime()),
                             SignalStore.misc().getLastKnownServerTimeOffset(),
                             remoteMessage.getPriority(),
                             remoteMessage.getOriginalPriority(),
                             NetworkUtil.getNetworkStatus(this)));

    String registrationChallenge = remoteMessage.getData().get("challenge");
    String rateLimitChallenge    = remoteMessage.getData().get("rateLimitChallenge");

    if (registrationChallenge != null) {
      handleRegistrationPushChallenge(registrationChallenge);
    } else if (rateLimitChallenge != null) {
      handleRateLimitPushChallenge(rateLimitChallenge);
    } else {
      handleReceivedNotification(AppDependencies.getApplication(), remoteMessage);
    }
  }

  @Override
  public void onDeletedMessages() {
    if (KeyCachingService.isLocked()) {
      return;
    }

    Log.w(TAG, "onDeleteMessages() -- Messages may have been dropped. Doing a normal message fetch.");
    handleReceivedNotification(AppDependencies.getApplication(), null);
  }

  @Override
  public void onNewToken(@NonNull String token) {
    Log.i(TAG, "onNewToken()");

    if (KeyCachingService.isLocked()) {
      TextSecurePreferences.setShouldRefreshFcmToken(AppDependencies.getApplication(), true);
      return;
    }

    if (!SignalStore.account().isRegistered()) {
      Log.i(TAG, "Got a new FCM token, but the user isn't registered.");
      return;
    }

    AppDependencies.getJobManager().add(new FcmRefreshJob());
  }

  @Override
  public void onMessageSent(@NonNull String s) {
    Log.i(TAG, "onMessageSent()" + s);
  }

  @Override
  public void onSendError(@NonNull String s, @NonNull Exception e) {
    Log.w(TAG, "onSendError()", e);
  }

  // MOLLY: Make this function public to use it from UnifiedPushReceiver
  public static void handleReceivedNotification(Context context, @Nullable RemoteMessage remoteMessage) {
    boolean highPriority = remoteMessage != null && remoteMessage.getPriority() == RemoteMessage.PRIORITY_HIGH;
    try {
      Log.d(TAG, String.format(Locale.US, "[handleReceivedNotification] API: %s, RemoteMessagePriority: %s", Build.VERSION.SDK_INT, remoteMessage != null ? remoteMessage.getPriority() : "n/a"));

      if (highPriority) {
        FcmFetchManager.startForegroundService(context);
      } else if (Build.VERSION.SDK_INT < 26) {
        FcmFetchManager.startBackgroundService(context);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to start service.", e);
      SignalLocalMetrics.FcmServiceStartFailure.onFcmFailedToStart();
    }

    FcmFetchManager.enqueueFetch(context, highPriority);
  }

  private static void handleRegistrationPushChallenge(@NonNull String challenge) {
    Log.d(TAG, "Got a registration push challenge.");
    PushChallengeRequest.postChallengeResponse(challenge);
  }

  private static void handleRateLimitPushChallenge(@NonNull String challenge) {
    Log.d(TAG, "Got a rate limit push challenge.");
    AppDependencies.getJobManager().add(new SubmitRateLimitPushChallengeJob(challenge));
  }
}