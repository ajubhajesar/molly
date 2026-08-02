package org.thoughtcrime.securesms.util;

import android.os.Handler;
import android.os.Looper;

import org.signal.core.util.logging.Log;

/**
 * AJ fork diagnostic: this build has no adb/logcat access, so a real ANR's stack trace is
 * completely invisible - Molly's own debug log only captures what Molly itself logs via
 * Log.i/w/e, not system_server's "ANR in ..." traces. This watchdog closes that gap.
 *
 * How it works: the main thread re-posts a "canary" to itself every CANARY_INTERVAL_MS,
 * stamping the time it last ran. A daemon watcher thread polls that timestamp every
 * POLL_INTERVAL_MS. If it's gone stale past HANG_THRESHOLD_MS, the main thread is currently
 * blocked - the watcher grabs Looper.getMainLooper().getThread().getStackTrace() (this is
 * safe to call from another thread; it doesn't require the target thread's cooperation) and
 * logs the full frame list, so it lands in the same debug log export already in use. Logs
 * once per hang (not spammed every poll) and re-arms once the main thread recovers.
 */
public class MainThreadWatchdog {

  private static final String TAG = Log.tag(MainThreadWatchdog.class);

  private static final long CANARY_INTERVAL_MS = 1000;
  private static final long POLL_INTERVAL_MS   = 500;
  private static final long HANG_THRESHOLD_MS  = 3000;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Thread  watcherThread;

  private volatile long    lastCanaryAt      = System.currentTimeMillis();
  private volatile boolean warnedForThisHang = false;

  public MainThreadWatchdog() {
    watcherThread = new Thread(this::watch, "signal-MainThreadWatchdog");
    watcherThread.setDaemon(true);
  }

  public void start() {
    postCanary();
    watcherThread.start();
    Log.i(TAG, "Started (hangThreshold=" + HANG_THRESHOLD_MS + "ms, pollInterval=" + POLL_INTERVAL_MS + "ms).");
  }

  private void postCanary() {
    mainHandler.post(() -> {
      lastCanaryAt = System.currentTimeMillis();
      if (warnedForThisHang) {
        Log.i(TAG, "Main thread recovered.");
        warnedForThisHang = false;
      }
      mainHandler.postDelayed(this::postCanary, CANARY_INTERVAL_MS);
    });
  }

  private void watch() {
    while (!Thread.interrupted()) {
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException e) {
        return;
      }

      long sinceLastCanary = System.currentTimeMillis() - lastCanaryAt;

      if (sinceLastCanary > HANG_THRESHOLD_MS && !warnedForThisHang) {
        warnedForThisHang = true;
        dumpMainThreadStack(sinceLastCanary);
      }
    }
  }

  private void dumpMainThreadStack(long hangDurationMs) {
    StringBuilder      sb    = new StringBuilder();
    StackTraceElement[] trace = Looper.getMainLooper().getThread().getStackTrace();

    sb.append("Main thread appears hung - no response for ").append(hangDurationMs).append("ms:\n");
    for (StackTraceElement element : trace) {
      sb.append("    at ").append(element).append("\n");
    }

    Log.w(TAG, sb.toString());
  }
}
