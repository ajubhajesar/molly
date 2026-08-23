package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
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
 * AJ fork: sends one live-typing draft-buffer update. Caller (LiveTypingCoordinator) is
 * responsible for throttling on the way in; this job additionally clears out any not-yet-run
 * job still sitting in its own queue before enqueuing itself, so a slow network can never leave
 * a backlog of stale drafts waiting to be sent one at a time - only the latest buffer state
 * ever actually goes out. An empty string is a valid, meaningful payload: it clears the peer's
 * preview (e.g. after sending the message or clearing the compose field).
 */
public class LiveTypingTextSendJob extends BaseJob {

  public static final String KEY = "LiveTypingTextSendJob";

  private static final String TAG = Log.tag(LiveTypingTextSendJob.class);

  private static final String KEY_THREAD_ID = "thread_id";
  private static final String KEY_TEXT      = "text";

  private long   threadId;
  private String text;

  public LiveTypingTextSendJob(long threadId, @NonNull String text) {
    this(new Job.Parameters.Builder()
                           .setQueue(getQueue(threadId))
                           .setMaxAttempts(1)
                           .setLifespan(TimeUnit.SECONDS.toMillis(5))
                           .addConstraint(NetworkConstraint.KEY)
                           .addConstraint(SealedSenderConstraint.KEY)
                           .setMemoryOnly(true)
                           .build(),
         threadId,
         text);

    // Coalesce: drop any earlier, now-stale draft update still waiting in this thread's text
    // queue. Only ever the most recent keystroke state should hit the wire.
    AppDependencies.getJobManager().cancelAllInQueue(getQueue(threadId));
  }

  public static String getQueue(long threadId) {
    return "LIVETYPE_TEXT_" + threadId;
  }

  private LiveTypingTextSendJob(@NonNull Job.Parameters parameters, long threadId, @NonNull String text) {
    super(parameters);

    this.threadId = threadId;
    this.text     = text;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_THREAD_ID, threadId)
                                    .putString(KEY_TEXT, text)
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
      Log.w(TAG, "Tried to send live-typing text to a non-existent thread.");
      return;
    }

    if (recipient.isBlocked()) {
      Log.w(TAG, "Not sending live-typing text to blocked recipients.");
      return;
    }

    if (recipient.isSelf()) {
      Log.w(TAG, "Not sending live-typing text to self.");
      return;
    }

    if (recipient.isGroup()) {
      Log.w(TAG, "Not sending live-typing text to a group thread.");
      return;
    }

    if (!recipient.isRegistered()) {
      Log.w(TAG, "Not sending live-typing text to non-Signal recipients.");
      return;
    }

    List<Recipient>  recipients = Collections.singletonList(recipient);
    Optional<byte[]> groupId    = Optional.empty();

    recipients = RecipientUtil.getEligibleForSending(Stream.of(recipients)
                                                           .map(Recipient::resolve)
                                                           .toList());

    SignalServiceTypingMessage textMessage = new SignalServiceTypingMessage(Action.LIVE_TEXT_UPDATE, System.currentTimeMillis(), groupId, Optional.of(text));

    GroupSendUtil.sendTypingMessage(context,
                                    recipient.getGroupId().map(GroupId::requireV2).orElse(null),
                                    recipients,
                                    textMessage,
                                    this::isCanceled);
  }

  @Override
  public void onFailure() {
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  public static final class Factory implements Job.Factory<LiveTypingTextSendJob> {
    @Override
    public @NonNull LiveTypingTextSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new LiveTypingTextSendJob(parameters, data.getLong(KEY_THREAD_ID), data.getString(KEY_TEXT));
    }
  }
}
