package org.thoughtcrime.securesms.components;

import android.annotation.SuppressLint;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobs.TypingSendJob;
import org.signal.core.util.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SuppressLint("UseSparseArrays")
public class TypingStatusSender {

  private static final String TAG = Log.tag(TypingStatusSender.class);

  private static final long REFRESH_TYPING_TIMEOUT = TimeUnit.SECONDS.toMillis(10);
  private static final long PAUSE_TYPING_TIMEOUT   = TimeUnit.SECONDS.toMillis(3);

  private static final long HEARTBEAT_INTERVAL = TimeUnit.SECONDS.toMillis(10);

  private final Map<Long, TimerPair> selfTypingTimers;
  private final Map<Long, Runnable>  heartbeatRunnables;

  public TypingStatusSender() {
    this.selfTypingTimers  = new HashMap<>();
    this.heartbeatRunnables = new HashMap<>();
  }

  public synchronized void onTypingStarted(long threadId) {
    TimerPair pair = Util.getOrDefault(selfTypingTimers, threadId, new TimerPair());
    selfTypingTimers.put(threadId, pair);

    if (pair.getStart() == null) {
      sendTyping(threadId, true);

      Runnable start = new StartRunnable(threadId);
      ThreadUtil.runOnMainDelayed(start, REFRESH_TYPING_TIMEOUT);
      pair.setStart(start);
    }

    if (pair.getStop() != null) {
      ThreadUtil.cancelRunnableOnMain(pair.getStop());
    }

    Runnable stop = () -> onTypingStopped(threadId, true);
    ThreadUtil.runOnMainDelayed(stop, PAUSE_TYPING_TIMEOUT);
    pair.setStop(stop);
  }

  public synchronized void onTypingStopped(long threadId) {
    onTypingStopped(threadId, false);
  }

  public synchronized void onTypingStoppedWithNotify(long threadId) {
    onTypingStopped(threadId, true);
  }

  private synchronized void onTypingStopped(long threadId, boolean notify) {
    TimerPair pair = Util.getOrDefault(selfTypingTimers, threadId, new TimerPair());
    selfTypingTimers.put(threadId, pair);

    if (pair.getStart() != null) {
      ThreadUtil.cancelRunnableOnMain(pair.getStart());

      if (notify) {
        sendTyping(threadId, false);
      }
    }

    if (pair.getStop() != null) {
      ThreadUtil.cancelRunnableOnMain(pair.getStop());
    }

    pair.setStart(null);
    pair.setStop(null);
  }

  private void sendTyping(long threadId, boolean typingStarted) {
    AppDependencies.getJobManager().add(new TypingSendJob(threadId, typingStarted));
  }

  /**
   * AJ fork: "active in chat" derived from the existing, proven-reliable typing
   * STARTED signal - no separate proto needed. Fires STARTED once immediately
   * on resume, then repeats every HEARTBEAT_INTERVAL while the screen stays
   * open. Real typing keystrokes call onTypingStarted() independently and
   * naturally piggyback on the exact same wire signal; the receiving side
   * (TypingStatusRepository) tells real typing apart from the idle heartbeat
   * by how close together consecutive STARTED signals from the same author
   * arrive. On pause, the heartbeat stops and the normal 3s typing-pause
   * timeout (if mid-keystroke) plus the receiver's own active-timeout will
   * naturally let the peer's indicator go inactive within ~15s.
   */
  public synchronized void onConversationResumed(long threadId) {
    Runnable existing = heartbeatRunnables.get(threadId);
    if (existing != null) {
      ThreadUtil.cancelRunnableOnMain(existing);
    }

    Runnable heartbeat = new HeartbeatRunnable(threadId);
    heartbeatRunnables.put(threadId, heartbeat);
    heartbeat.run(); // fires immediately and reschedules itself
  }

  public synchronized void onConversationPaused(long threadId) {
    Runnable heartbeat = heartbeatRunnables.remove(threadId);
    if (heartbeat != null) {
      ThreadUtil.cancelRunnableOnMain(heartbeat);
    }
  }

  private class HeartbeatRunnable implements Runnable {

    private final long threadId;

    private HeartbeatRunnable(long threadId) {
      this.threadId = threadId;
    }

    @Override
    public void run() {
      sendTyping(threadId, true);
      ThreadUtil.runOnMainDelayed(this, HEARTBEAT_INTERVAL);
    }
  }

  private class StartRunnable implements Runnable {

    private final long threadId;

    private StartRunnable(long threadId) {
      this.threadId = threadId;
    }

    @Override
    public void run() {
      sendTyping(threadId, true);
      ThreadUtil.runOnMainDelayed(this, REFRESH_TYPING_TIMEOUT);
    }
  }

  private static class TimerPair {
    private Runnable start;
    private Runnable stop;

    public Runnable getStart() {
      return start;
    }

    public void setStart(Runnable start) {
      this.start = start;
    }

    public Runnable getStop() {
      return stop;
    }

    public void setStop(Runnable stop) {
      this.stop = stop;
    }
  }
}
