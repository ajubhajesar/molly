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
 * AJ fork: sent once when this device's own connection is restored while a conversation is
 * open, asking the peer to reply with their true current active-status state. Necessary
 * because PRESENT/NOT_PRESENT ride the same wire message type as typing indicators, which the
 * server does not queue for offline delivery - a peer's last signal can be silently dropped if
 * this device was disconnected at that exact moment, with no way to know it happened. This is
 * the other direction from ActiveStatusSendJob re-announcing on reconnect: that fixes "my last
 * send might not have landed", this fixes "I might have missed their last send".
 */
public class RequestPresenceSendJob extends BaseJob {

  public static final String KEY = "RequestPresenceSendJob";

  private static final String TAG = Log.tag(RequestPresenceSendJob.class);

  private static final String KEY_THREAD_ID = "thread_id";

  private long threadId;

  public RequestPresenceSendJob(long threadId) {
    this(new Job.Parameters.Builder()
                           .setQueue(getQueue(threadId))
                           .setMaxAttempts(1)
                           .setLifespan(TimeUnit.SECONDS.toMillis(5))
                           .addConstraint(NetworkConstraint.KEY)
                           .addConstraint(SealedSenderConstraint.KEY)
                           .setMemoryOnly(true)
                           .build(),
         threadId);
  }

  public static String getQueue(long threadId) {
    return "ACTIVE_STATUS_" + threadId;
  }

  private RequestPresenceSendJob(@NonNull Job.Parameters parameters, long threadId) {
    super(parameters);

    this.threadId = threadId;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_THREAD_ID, threadId)
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

    Log.d(TAG, "Sending presence request for thread " + threadId);

    Recipient recipient = SignalDatabase.threads().getRecipientForThreadId(threadId);

    if (recipient == null) {
      Log.w(TAG, "Tried to request presence on a non-existent thread.");
      return;
    }

    if (recipient.isBlocked()) {
      Log.w(TAG, "Not requesting presence from blocked recipients.");
      return;
    }

    if (recipient.isSelf()) {
      Log.w(TAG, "Not requesting presence from self.");
      return;
    }

    if (recipient.isPushV1Group() || recipient.isMmsGroup()) {
      Log.w(TAG, "Not requesting presence on unsupported groups.");
      return;
    }

    if (recipient.isPushV2Group() && !SignalDatabase.groups().isActive(recipient.requireGroupId())) {
      Log.w(TAG, "Not requesting presence on terminated or inactive groups.");
      return;
    }

    if (!recipient.isRegistered()) {
      Log.w(TAG, "Not requesting presence from non-Signal recipients.");
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

    SignalServiceTypingMessage requestMessage = new SignalServiceTypingMessage(Action.REQUEST_PRESENCE, System.currentTimeMillis(), groupId);

    GroupSendUtil.sendTypingMessage(context,
                                    recipient.getGroupId().map(GroupId::requireV2).orElse(null),
                                    recipients,
                                    requestMessage,
                                    this::isCanceled);
  }

  @Override
  public void onFailure() {
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  public static final class Factory implements Job.Factory<RequestPresenceSendJob> {
    @Override
    public @NonNull RequestPresenceSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new RequestPresenceSendJob(parameters, data.getLong(KEY_THREAD_ID));
    }
  }
}
