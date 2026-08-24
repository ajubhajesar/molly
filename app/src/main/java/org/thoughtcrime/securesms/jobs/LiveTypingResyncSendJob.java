package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobmanager.impl.SealedSenderConstraint;
import org.thoughtcrime.securesms.messages.GroupSendUtil;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage.Action;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * AJ fork: sends the live-typing resync trio - REQUEST/ACTIVE/NONE. Separate from
 * LiveTypingControlSendJob (the REQUEST/ACCEPT/DECLINE/STOP handshake) on purpose: a resync
 * reply is a truthful status report, not a handshake step, and mixing the two into one queue
 * risks a resync REQUEST getting coalesced against an unrelated handshake action for the same
 * thread. Own queue, same memory-only/single-attempt treatment as every other presence signal.
 */
public class LiveTypingResyncSendJob extends BaseJob {

  public static final String KEY = "LiveTypingResyncSendJob";

  private static final String TAG = Log.tag(LiveTypingResyncSendJob.class);

  private static final String KEY_THREAD_ID = "thread_id";
  private static final String KEY_MESSAGE   = "resync_message";

  public enum ResyncMessage {
    REQUEST, ACTIVE, NONE
  }

  private long          threadId;
  private ResyncMessage resyncMessage;

  public LiveTypingResyncSendJob(long threadId, @NonNull ResyncMessage resyncMessage) {
    this(new Job.Parameters.Builder()
                           .setQueue(getQueue(threadId))
                           .setMaxAttempts(1)
                           .setLifespan(TimeUnit.SECONDS.toMillis(5))
                           .addConstraint(NetworkConstraint.KEY)
                           .addConstraint(SealedSenderConstraint.KEY)
                           .setMemoryOnly(true)
                           .build(),
         threadId,
         resyncMessage);
  }

  public static String getQueue(long threadId) {
    return "LIVETYPE_RESYNC_" + threadId;
  }

  private LiveTypingResyncSendJob(@NonNull Job.Parameters parameters, long threadId, @NonNull ResyncMessage resyncMessage) {
    super(parameters);

    this.threadId      = threadId;
    this.resyncMessage = resyncMessage;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_THREAD_ID, threadId)
                                    .putString(KEY_MESSAGE, resyncMessage.name())
                                    .serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws Exception {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
      return;
    }

    Recipient recipient = SignalDatabase.threads().getRecipientForThreadId(threadId);

    if (recipient == null) {
      Log.w(TAG, "Tried to send live-typing resync to a non-existent thread.");
      return;
    }

    if (recipient.isBlocked()) {
      Log.w(TAG, "Not sending live-typing resync to blocked recipients.");
      return;
    }

    if (recipient.isSelf()) {
      Log.w(TAG, "Not sending live-typing resync to self.");
      return;
    }

    if (recipient.isGroup()) {
      Log.w(TAG, "Not sending live-typing resync to a group thread.");
      return;
    }

    if (!recipient.isRegistered()) {
      Log.w(TAG, "Not sending live-typing resync to non-Signal recipients.");
      return;
    }

    List<Recipient>  recipients = Collections.singletonList(recipient);
    Optional<byte[]> groupId    = Optional.empty();

    recipients = RecipientUtil.getEligibleForSending(Stream.of(recipients)
                                                           .map(Recipient::resolve)
                                                           .toList());

    Action action;
    switch (resyncMessage) {
      case REQUEST: action = Action.LIVE_TEXT_RESYNC_REQUEST; break;
      case ACTIVE:  action = Action.LIVE_TEXT_RESYNC_ACTIVE;  break;
      case NONE:    action = Action.LIVE_TEXT_RESYNC_NONE;    break;
      default:      throw new IllegalStateException("Unknown resync message: " + resyncMessage);
    }

    SignalServiceTypingMessage resyncSignal = new SignalServiceTypingMessage(action, System.currentTimeMillis(), groupId);

    Log.d(TAG, "Sending live-typing resync " + resyncMessage + " for thread " + threadId);

    GroupSendUtil.sendTypingMessage(context,
                                    recipient.getGroupId().map(GroupId::requireV2).orElse(null),
                                    recipients,
                                    resyncSignal,
                                    this::isCanceled);
  }

  @Override
  public void onFailure() {
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  public static final class Factory implements Job.Factory<LiveTypingResyncSendJob> {
    @Override
    public @NonNull LiveTypingResyncSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new LiveTypingResyncSendJob(parameters, data.getLong(KEY_THREAD_ID), ResyncMessage.valueOf(data.getString(KEY_MESSAGE)));
    }
  }
}
