package org.thoughtcrime.securesms.components

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.signal.core.util.ThreadUtil
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.LiveTypingControlSendJob
import org.thoughtcrime.securesms.jobs.LiveTypingTextSendJob
import org.thoughtcrime.securesms.recipients.Recipient
import java.util.concurrent.TimeUnit

/**
 * AJ fork: live-typing consent handshake + draft-sharing - presence style 5 ("live"). Kept
 * fully separate from TypingStatusRepository/ActiveStatusSender because this is the one style
 * where BOTH sides must explicitly agree before either sees anything. Every other style
 * (cat/lines/bubble/text) is a purely local rendering choice each side picks independently;
 * this one exposes actual draft content - corrections, unsent thoughts - so it needs real
 * mutual opt-in, not just a preference flip.
 *
 * State is memory-only, same as PRESENT/NOT_PRESENT in TypingStatusRepository, deliberately -
 * it does not survive an app restart. That's a feature, not a gap: live typing can never be
 * "silently still on" from a session the user forgot about. Every fresh app session starts at
 * NONE and needs a fresh request, so there's always a visible consent moment before any text
 * is ever shared, and never a persisted flag to accidentally leave enabled.
 */
class LiveTypingCoordinator {

  enum class State {
    NONE,               // not active, nothing pending
    REQUESTED_BY_ME,    // I asked, waiting on their ACCEPT/DECLINE
    REQUESTED_BY_PEER,  // they asked, waiting on my ACCEPT/DECLINE - UI shows the consent popup
    ACTIVE              // both sides agreed, live text flows both ways
  }

  companion object {
    private val TAG = Log.tag(LiveTypingCoordinator::class.java)
    private const val REQUEST_TIMEOUT_MS = 10_000L
    private const val TEXT_SEND_THROTTLE_MS = 200L
  }

  private val states: MutableMap<Long, State> = HashMap()
  private val stateNotifiers: MutableMap<Long, MutableLiveData<State>> = HashMap()
  private val liveTextNotifiers: MutableMap<Long, MutableLiveData<String>> = HashMap()
  private val requestTimeouts: MutableMap<Long, Runnable> = HashMap()
  private val pendingLocalText: MutableMap<Long, String> = HashMap()
  private val lastSendTime: MutableMap<Long, Long> = HashMap()
  private val throttleRunnables: MutableMap<Long, Runnable> = HashMap()

  @Synchronized
  fun getState(threadId: Long): LiveData<State> {
    var notifier = stateNotifiers[threadId]
    if (notifier == null) {
      notifier = MutableLiveData(states[threadId] ?: State.NONE)
      stateNotifiers[threadId] = notifier
    }
    return notifier
  }

  @Synchronized
  fun getLiveText(threadId: Long): LiveData<String> {
    var notifier = liveTextNotifiers[threadId]
    if (notifier == null) {
      notifier = MutableLiveData("")
      liveTextNotifiers[threadId] = notifier
    }
    return notifier
  }

  @Synchronized
  fun currentState(threadId: Long): State = states[threadId] ?: State.NONE

  /** UI asked to switch into the live style (e.g. long-press cycled into it). Asks the peer - does not activate locally yet. */
  @Synchronized
  fun requestLiveTyping(threadId: Long) {
    if (currentState(threadId) != State.NONE) {
      Log.w(TAG, "requestLiveTyping() called while not NONE for thread $threadId - ignoring.")
      return
    }
    setState(threadId, State.REQUESTED_BY_ME)
    AppDependencies.jobManager.add(LiveTypingControlSendJob(threadId, LiveTypingControlSendJob.ControlAction.REQUEST))

    val timeout = Runnable { onRequestTimedOut(threadId) }
    requestTimeouts.remove(threadId)?.let { ThreadUtil.cancelRunnableOnMain(it) }
    requestTimeouts[threadId] = timeout
    ThreadUtil.runOnMainDelayed(timeout, REQUEST_TIMEOUT_MS)
  }

  @Synchronized
  private fun onRequestTimedOut(threadId: Long) {
    requestTimeouts.remove(threadId)
    if (currentState(threadId) == State.REQUESTED_BY_ME) {
      Log.i(TAG, "Live-typing request for thread $threadId timed out with no response.")
      setState(threadId, State.NONE)
    }
  }

  /** Consent popup accepted. */
  @Synchronized
  fun acceptLiveTyping(threadId: Long) {
    if (currentState(threadId) != State.REQUESTED_BY_PEER) {
      Log.w(TAG, "acceptLiveTyping() called while not REQUESTED_BY_PEER for thread $threadId - ignoring.")
      return
    }
    setState(threadId, State.ACTIVE)
    AppDependencies.jobManager.add(LiveTypingControlSendJob(threadId, LiveTypingControlSendJob.ControlAction.ACCEPT))
  }

  /** Consent popup declined. */
  @Synchronized
  fun declineLiveTyping(threadId: Long) {
    if (currentState(threadId) != State.REQUESTED_BY_PEER) {
      Log.w(TAG, "declineLiveTyping() called while not REQUESTED_BY_PEER for thread $threadId - ignoring.")
      return
    }
    setState(threadId, State.NONE)
    AppDependencies.jobManager.add(LiveTypingControlSendJob(threadId, LiveTypingControlSendJob.ControlAction.DECLINE))
  }

  /** Either side can end an ACTIVE (or still-pending) session at any time - no re-request needed to turn it back off. */
  @Synchronized
  fun stopLiveTyping(threadId: Long) {
    val wasActiveOrPending = currentState(threadId) != State.NONE
    requestTimeouts.remove(threadId)?.let { ThreadUtil.cancelRunnableOnMain(it) }
    throttleRunnables.remove(threadId)?.let { ThreadUtil.cancelRunnableOnMain(it) }
    pendingLocalText.remove(threadId)
    setState(threadId, State.NONE)
    liveTextNotifiers[threadId]?.postValue("")
    if (wasActiveOrPending) {
      AppDependencies.jobManager.add(LiveTypingControlSendJob(threadId, LiveTypingControlSendJob.ControlAction.STOP))
    }
  }

  // ---- incoming wire signals - called from MessageContentProcessor ----

  @Synchronized
  fun onRequestReceived(threadId: Long, author: Recipient) {
    if (author.isSelf) return
    when (currentState(threadId)) {
      State.ACTIVE, State.REQUESTED_BY_PEER -> return // nothing new
      State.REQUESTED_BY_ME -> {
        // Both sides requested at once - mutual intent already established, skip the popup.
        Log.i(TAG, "Simultaneous live-typing request on thread $threadId - auto-accepting.")
        requestTimeouts.remove(threadId)?.let { ThreadUtil.cancelRunnableOnMain(it) }
        setState(threadId, State.ACTIVE)
        AppDependencies.jobManager.add(LiveTypingControlSendJob(threadId, LiveTypingControlSendJob.ControlAction.ACCEPT))
      }
      State.NONE -> setState(threadId, State.REQUESTED_BY_PEER)
    }
  }

  @Synchronized
  fun onAcceptReceived(threadId: Long, author: Recipient) {
    if (author.isSelf) return
    if (currentState(threadId) != State.REQUESTED_BY_ME) {
      Log.w(TAG, "Got LIVE_TEXT_ACCEPT for thread $threadId while not REQUESTED_BY_ME - ignoring.")
      return
    }
    requestTimeouts.remove(threadId)?.let { ThreadUtil.cancelRunnableOnMain(it) }
    setState(threadId, State.ACTIVE)
  }

  @Synchronized
  fun onDeclineReceived(threadId: Long, author: Recipient) {
    if (author.isSelf) return
    requestTimeouts.remove(threadId)?.let { ThreadUtil.cancelRunnableOnMain(it) }
    if (currentState(threadId) == State.REQUESTED_BY_ME) {
      setState(threadId, State.NONE)
    }
  }

  @Synchronized
  fun onStopReceived(threadId: Long, author: Recipient) {
    if (author.isSelf) return
    requestTimeouts.remove(threadId)?.let { ThreadUtil.cancelRunnableOnMain(it) }
    setState(threadId, State.NONE)
    liveTextNotifiers[threadId]?.postValue("")
  }

  @Synchronized
  fun onLiveTextReceived(threadId: Long, author: Recipient, text: String) {
    if (author.isSelf) return
    if (currentState(threadId) != State.ACTIVE) {
      // Stray update after a STOP/DECLINE crossed on the wire - never surface text outside
      // an ACTIVE session on this end.
      return
    }
    getLiveText(threadId)
    liveTextNotifiers[threadId]?.postValue(text)
  }

  // ---- outgoing text - called from the compose EditText watcher while ACTIVE ----

  /** Throttled: coalesces bursts of keystrokes into roughly one send per TEXT_SEND_THROTTLE_MS. */
  @Synchronized
  fun onLocalTextChanged(threadId: Long, text: String) {
    if (currentState(threadId) != State.ACTIVE) {
      return
    }
    pendingLocalText[threadId] = text

    if (throttleRunnables.containsKey(threadId)) {
      return // a flush is already scheduled and will pick up the latest text above
    }

    val now = System.currentTimeMillis()
    val last = lastSendTime[threadId] ?: 0L
    val delay = (TEXT_SEND_THROTTLE_MS - (now - last)).coerceAtLeast(0L)

    val flush = Runnable { flushLocalText(threadId) }
    throttleRunnables[threadId] = flush
    ThreadUtil.runOnMainDelayed(flush, delay)
  }

  @Synchronized
  private fun flushLocalText(threadId: Long) {
    throttleRunnables.remove(threadId)
    if (currentState(threadId) != State.ACTIVE) {
      return
    }
    val text = pendingLocalText[threadId] ?: ""
    lastSendTime[threadId] = System.currentTimeMillis()
    AppDependencies.jobManager.add(LiveTypingTextSendJob(threadId, text))
  }

  @Synchronized
  fun clear() {
    for (timeout in requestTimeouts.values) ThreadUtil.cancelRunnableOnMain(timeout)
    for (throttle in throttleRunnables.values) ThreadUtil.cancelRunnableOnMain(throttle)
    requestTimeouts.clear()
    throttleRunnables.clear()
    states.clear()
    pendingLocalText.clear()
    lastSendTime.clear()
    for (notifier in stateNotifiers.values) notifier.postValue(State.NONE)
    for (notifier in liveTextNotifiers.values) notifier.postValue("")
  }

  @Synchronized
  private fun setState(threadId: Long, state: State) {
    states[threadId] = state
    var notifier = stateNotifiers[threadId]
    if (notifier == null) {
      notifier = MutableLiveData()
      stateNotifiers[threadId] = notifier
    }
    Log.d(TAG, "Thread $threadId live-typing state -> $state")
    notifier.postValue(state)
  }
}
