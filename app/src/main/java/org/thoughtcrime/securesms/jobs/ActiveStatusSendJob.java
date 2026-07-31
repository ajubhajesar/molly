package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GroupTable;
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
 * AJ fork: sends the "active status" presence signal - PRESENT once when a conversation is
 * opened, NOT_PRESENT once when it's closed. Deliberately not a heartbeat: no repeat send,
 * no periodic refresh, and no receiving-side timeout fallback (see
 * TypingStatusRepository.onPresent/onAbsent). Completely separate wire action from the real
 * typing STARTED/STOPPED signal, so it never gets confused with actual typing.
 */
public class ActiveStatusSendJob extends BaseJob {

  public static final String KEY = "ActiveStatusSendJob";

  private static final String TAG = Log.tag(ActiveStatusSendJob.class);

  private static final String KEY_THREAD_ID = "thread_id";
  private static final String KEY_PRESENT   = "present";

  private long    threadId;
  private boolean present;

  public ActiveStatusSendJob(long threadId, boolean present) {
    this(new Job.Parameters.Builder()
                           .setQueue(getQueue(threadId))
                           .setMaxAttempts(1)
                           .setLifespan(TimeUnit.SECONDS.toMillis(5))
                           .addConstraint(NetworkConstraint.KEY)
                           .addConstraint(SealedSenderConstraint.KEY)
                           .setMemoryOnly(true)
                           .build(),
         threadId,
         present);
  }

  public static String getQueue(long threadId) {
    return "ACTIVE_STATUS_" + threadId;
  }

  private ActiveStatusSendJob(@NonNull Job.Parameters parameters, long threadId, boolean present) {
    super(parameters);

    this.threadId = threadId;
    this.present  = present;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_THREAD_ID, threadId)
                                    .putBoolean(KEY_PRESENT, present)
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

    Log.d(TAG, "Sending active status " + (present ? "PRESENT" : "NOT_PRESENT") + " for thread " + threadId);

    Recipient recipient = SignalDatabase.threads().getRecipientForThreadId(threadId);

    if (recipient == null) {
      Log.w(TAG, "Tried to send active status to a non-existent thread.");
      return;
    }

    if (recipient.isBlocked()) {
      Log.w(TAG, "Not sending active status to blocked recipients.");
      return;
    }

    if (recipient.isSelf()) {
      Log.w(TAG, "Not sending active status to self.");
      return;
    }

    if (recipient.isPushV1Group() || recipient.isMmsGroup()) {
      Log.w(TAG, "Not sending active status to unsupported groups.");
      return;
    }

    if (recipient.isPushV2Group() && !SignalDatabase.groups().isActive(recipient.requireGroupId())) {
      Log.w(TAG, "Not sending active status to terminated or inactive groups.");
      return;
    }

    if (!recipient.isRegistered()) {
      Log.w(TAG, "Not sending active status to non-Signal recipients.");
      return;
    }

    List<Recipient>  recipients = Collections.singletonList(recipient);
    Optional<byte[]> groupId    = Optional.empty();

    if (recipient.isGroup()) {
      recipients = SignalDatabase.groups().getGroupMembers(recipient.requireGroupId(), GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);
      groupId    = Optional.of(recipient.requireGroupId().getDecodedId());
    }

    recipients = RecipientUtil.getEligibleForSending(Stream.of(recipients)
                                                           .map(Recipient::resolve)
                                                           .toList());

    SignalServiceTypingMessage activeStatusMessage = new SignalServiceTypingMessage(present ? Action.PRESENT : Action.NOT_PRESENT, System.currentTimeMillis(), groupId);

    GroupSendUtil.sendTypingMessage(context,
                                    recipient.getGroupId().map(GroupId::requireV2).orElse(null),
                                    recipients,
                                    activeStatusMessage,
                                    this::isCanceled);
  }

  @Override
  public void onFailure() {
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  public static final class Factory implements Job.Factory<ActiveStatusSendJob> {
    @Override
    public @NonNull ActiveStatusSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new ActiveStatusSendJob(parameters, data.getLong(KEY_THREAD_ID), data.getBoolean(KEY_PRESENT));
    }
  }
}
