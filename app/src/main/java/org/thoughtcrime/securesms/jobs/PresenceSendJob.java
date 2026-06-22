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
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.whispersystems.signalservice.api.messages.SignalServicePresenceMessage;
import org.whispersystems.signalservice.api.messages.SignalServicePresenceMessage.Action;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * AJ fork: sends the "active in chat" presence signal. Fully independent of
 * TypingSendJob - has no relationship to typing whatsoever. Sent once when the
 * conversation screen is resumed (active=true) and once when paused (active=false).
 */
public class PresenceSendJob extends BaseJob {

  public static final String KEY = "PresenceSendJob";

  private static final String TAG = Log.tag(PresenceSendJob.class);

  private static final String KEY_THREAD_ID = "thread_id";
  private static final String KEY_ACTIVE    = "active";

  private long    threadId;
  private boolean active;

  public PresenceSendJob(long threadId, boolean active) {
    this(new Job.Parameters.Builder()
                           .setQueue(getQueue(threadId))
                           .setMaxAttempts(1)
                           .setLifespan(TimeUnit.SECONDS.toMillis(5))
                           .addConstraint(NetworkConstraint.KEY)
                           .addConstraint(SealedSenderConstraint.KEY)
                           .setMemoryOnly(true)
                           .build(),
         threadId,
         active);
  }

  public static String getQueue(long threadId) {
    return "PRESENCE_" + threadId;
  }

  private PresenceSendJob(@NonNull Job.Parameters parameters, long threadId, boolean active) {
    super(parameters);

    this.threadId = threadId;
    this.active   = active;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_THREAD_ID, threadId)
                                    .putBoolean(KEY_ACTIVE, active)
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

    Log.d(TAG, "Sending presence " + (active ? "active" : "inactive") + " for thread " + threadId);

    Recipient recipient = SignalDatabase.threads().getRecipientForThreadId(threadId);

    if (recipient == null) {
      Log.w(TAG, "Tried to send a presence message to a non-existent thread.");
      return;
    }

    if (recipient.isBlocked()) {
      Log.w(TAG, "Not sending presence to blocked recipients.");
      return;
    }

    if (recipient.isSelf()) {
      Log.w(TAG, "Not sending presence to self.");
      return;
    }

    if (recipient.isPushV1Group() || recipient.isMmsGroup()) {
      Log.w(TAG, "Not sending presence to unsupported groups.");
      return;
    }

    if (recipient.isPushV2Group() && !SignalDatabase.groups().isActive(recipient.requireGroupId())) {
      Log.w(TAG, "Not sending presence to terminated or inactive groups.");
      return;
    }

    if (!recipient.isRegistered()) {
      Log.w(TAG, "Not sending presence to non-Signal recipients.");
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

    SignalServicePresenceMessage presenceMessage = new SignalServicePresenceMessage(active ? Action.ACTIVE : Action.INACTIVE, System.currentTimeMillis(), groupId);

    // NOTE: 1:1-only send path (client-side fanout via sendPresence). Unlike
    // TypingSendJob, this does not use GroupSendUtil's sender-key group fanout -
    // fine for this fork's 2-person use case, but presence in actual groups
    // would need a sendGroupPresence equivalent added to SignalServiceMessageSender.
    SignalServiceMessageSender messageSender = AppDependencies.getSignalServiceMessageSender();

    List<SignalServiceAddress> addresses = RecipientUtil.toSignalServiceAddressesFromResolved(context, recipients);

    List<org.thoughtcrime.securesms.crypto.SealedSenderAccess> sealedSenderAccesses = Stream.of(recipients)
                                                  .map(SealedSenderAccessUtil::getSealedSenderAccessFor)
                                                  .toList();

    messageSender.sendPresence(addresses, sealedSenderAccesses, presenceMessage, this::isCanceled);
  }

  @Override
  public void onFailure() {
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  public static final class Factory implements Job.Factory<PresenceSendJob> {
    @Override
    public @NonNull PresenceSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new PresenceSendJob(parameters, data.getLong(KEY_THREAD_ID), data.getBoolean(KEY_ACTIVE));
    }
  }
}
