package org.thoughtcrime.securesms.components;

import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobs.ActiveStatusSendJob;

/**
 * AJ fork: fires the "active status" presence signal exactly twice per conversation visit -
 * PRESENT on resume, NOT_PRESENT on pause. No heartbeat, no repeat, no timeout fallback on
 * either end. See TypingStatusRepository.onPresent/onAbsent for the receiving side.
 */
public class ActiveStatusSender {

  public void onConversationResumed(long threadId) {
    AppDependencies.getJobManager().add(new ActiveStatusSendJob(threadId, true));
  }

  public void onConversationPaused(long threadId) {
    AppDependencies.getJobManager().add(new ActiveStatusSendJob(threadId, false));
  }
}
