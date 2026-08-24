package org.thoughtcrime.securesms.components

import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.ActiveStatusSendJob
import org.thoughtcrime.securesms.jobs.RequestPresenceSendJob
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState

/**
 * AJ fork: fires the "active status" presence signal - PRESENT once on resume, NOT_PRESENT
 * once on pause. Still exactly two signal types, no heartbeat, no timeout fallback on either
 * end - that part is unchanged from the original design.
 *
 * Two self-healing additions on top, both edge-triggered off a real reconnect event rather
 * than a timer or retry loop, because PRESENT/NOT_PRESENT ride the same wire message type as
 * typing indicators, which the server does not queue for offline delivery - either side's
 * last signal can be silently dropped if the other was disconnected at that exact moment,
 * with nothing telling the sender it didn't land:
 *  - This device's own connection comes back while a conversation is still open -> re-announce
 *    PRESENT, in case the last send never reached anyone (covers "my signal got lost").
 *  - A peer's connection comes back and it asks what this device's current state is for a
 *    thread -> reply with the truth (covers "their signal got lost" / "I missed theirs").
 */
class ActiveStatusSender {

  companion object {
    private val TAG = Log.tag(ActiveStatusSender::class.java)
  }

  @Volatile
  private var openThreadId: Long = -1

  private var reconnectDisposable: Disposable = Disposable.disposed()

  init {
    reconnectDisposable = AppDependencies.authWebSocket.state
      .observeOn(Schedulers.io())
      .subscribeBy(
        onError = { Log.w(TAG, "Error observing websocket state for active-status resync", it) },
        onNext = { state ->
          if (state == WebSocketConnectionState.CONNECTED) {
            onConnectionRestored()
          }
        }
      )
  }

  fun onConversationResumed(threadId: Long) {
    openThreadId = threadId
    AppDependencies.jobManager.add(ActiveStatusSendJob(threadId, true))
  }

  fun onConversationPaused(threadId: Long) {
    if (openThreadId == threadId) {
      openThreadId = -1
    }
    AppDependencies.jobManager.add(ActiveStatusSendJob(threadId, false))
  }

  /** This device's own connection just came back - resync both directions for the open thread. */
  private fun onConnectionRestored() {
    val threadId = openThreadId
    if (threadId == -1L) {
      return
    }

    Log.i(TAG, "Connection restored while thread $threadId is open - resyncing active status.")
    AppDependencies.jobManager.add(ActiveStatusSendJob(threadId, true))
    AppDependencies.jobManager.add(RequestPresenceSendJob(threadId))
    // AJ fork: same reconnect moment, same reasoning - resync any live-typing session this
    // device still believes is ACTIVE, in case the last ACCEPT/STOP got silently dropped.
    AppDependencies.liveTypingCoordinator.onConnectionRestored()
  }

  /**
   * A peer asked what this device's current state is for [threadId] - they just reconnected
   * and may have missed the last signal sent to them. Always reply with the truth, whichever
   * way it goes: PRESENT if the thread is genuinely open right now, NOT_PRESENT otherwise.
   * Replying unconditionally (not just when the answer is PRESENT) matters here - if the peer's
   * stale belief is the wrong one, silence would leave it uncorrected.
   */
  fun onPresenceRequested(threadId: Long) {
    val isOpen = openThreadId == threadId
    Log.i(TAG, "Presence requested for thread $threadId - replying ${if (isOpen) "PRESENT" else "NOT_PRESENT"}.")
    AppDependencies.jobManager.add(ActiveStatusSendJob(threadId, isOpen))
  }
}
