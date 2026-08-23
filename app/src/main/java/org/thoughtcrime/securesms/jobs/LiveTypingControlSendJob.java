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
 * AJ fork: sends the live-typing consent handshake signals - REQUEST/ACCEPT/DECLINE/STOP. Kept
 * as its own job class, separate from LiveTypingTextSendJob, because these four are handshake
 * events that must each land (or genuinely fail) on their own - unlike text updates, a control
 * signal should never be silently superseded by a later one in the same queue.
 */
public class LiveTypingControlSendJob extends BaseJob {

  public static final String KEY = "LiveTypingControlSendJob";

  private static final String TAG = Log.tag(LiveTypingControlSendJob.class);

  private static final String KEY_THREAD_ID = "thread_id";
  private static final String KEY_CONTROL   = "control_action";

  public enum ControlAction {
    REQUEST, ACCEPT, DECLINE, STOP
  }

  private long          threadId;
  private ControlAction controlAction;

  public LiveTypingControlSendJob(long threadId, @NonNull ControlAction controlAction) {
    this(new Job.Parameters.Builder()
                           .setQueue(getQueue(threadId))
                           .setMaxAttempts(1)
                           .setLifespan(TimeUnit.SECONDS.toMillis(5))
                           .addConstraint(NetworkConstraint.KEY)
                           .addConstraint(SealedSenderConstraint.KEY)
                           .setMemoryOnly(true)
                           .build(),
         threadId,
         controlAction);
  }

  public static String getQueue(long threadId) {
    return "LIVETYPE_CTRL_" + threadId;
  }

  private LiveTypingControlSendJob(@NonNull Job.Parameters parameters, long threadId, @NonNull ControlAction controlAction) {
    super(parameters);

    this.threadId      = threadId;
    this.controlAction = controlAction;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_THREAD_ID, threadId)
                                    .putString(KEY_CONTROL, controlAction.name())
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

    Log.d(TAG, "Sending live-typing " + controlAction + " for thread " + threadId);

    Recipient recipient = SignalDatabase.threads().getRecipientForThreadId(threadId);

    if (recipient == null) {
      Log.w(TAG, "Tried to send live-typing control to a non-existent thread.");
      return;
    }

    if (recipient.isBlocked()) {
      Log.w(TAG, "Not sending live-typing control to blocked recipients.");
      return;
    }

    if (recipient.isSelf()) {
      Log.w(TAG, "Not sending live-typing control to self.");
      return;
    }

    // AJ fork: live typing is a 1:1 consent contract between two people, deliberately not
    // extended to groups - "both sides aware" doesn't have a clean meaning once there are
    // more than two participants.
    if (recipient.isGroup()) {
      Log.w(TAG, "Not sending live-typing control to a group thread.");
      return;
    }

    if (!recipient.isRegistered()) {
      Log.w(TAG, "Not sending live-typing control to non-Signal recipients.");
      return;
    }

    List<Recipient>  recipients = Collections.singletonList(recipient);
    Optional<byte[]> groupId    = Optional.empty();

    recipients = RecipientUtil.getEligibleForSending(Stream.of(recipients)
                                                           .map(Recipient::resolve)
                                                           .toList());

    Action action;
    switch (controlAction) {
      case REQUEST: action = Action.LIVE_TEXT_REQUEST; break;
      case ACCEPT:  action = Action.LIVE_TEXT_ACCEPT;  break;
      case DECLINE: action = Action.LIVE_TEXT_DECLINE; break;
      case STOP:    action = Action.LIVE_TEXT_STOP;    break;
      default:      throw new IllegalStateException("Unknown control action: " + controlAction);
    }

    SignalServiceTypingMessage controlMessage = new SignalServiceTypingMessage(action, System.currentTimeMillis(), groupId);

    GroupSendUtil.sendTypingMessage(context,
                                    recipient.getGroupId().map(GroupId::requireV2).orElse(null),
                                    recipients,
                                    controlMessage,
                                    this::isCanceled);
  }

  @Override
  public void onFailure() {
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  public static final class Factory implements Job.Factory<LiveTypingControlSendJob> {
    @Override
    public @NonNull LiveTypingControlSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new LiveTypingControlSendJob(parameters, data.getLong(KEY_THREAD_ID), ControlAction.valueOf(data.getString(KEY_CONTROL)));
    }
  }
}
