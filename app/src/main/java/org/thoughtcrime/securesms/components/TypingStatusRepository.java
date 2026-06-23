package org.thoughtcrime.securesms.components;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.signal.core.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@SuppressLint("UseSparseArrays")
public class TypingStatusRepository {

  private static final String TAG = Log.tag(TypingStatusRepository.class);

  private static final long RECIPIENT_TYPING_TIMEOUT = TimeUnit.SECONDS.toMillis(15);

  // AJ fork: heartbeat-based active-status derived entirely from the existing,
  // proven-reliable typing STARTED/STOPPED signal - no separate proto needed.
  // The sender fires STARTED every ~10s while the screen is open (heartbeat) in
  // addition to real per-keystroke STARTED calls. We classify each STARTED by
  // the time since the last one from the same author: a short gap means real
  // typing (keystrokes refresh every ~10s while continuously typing, and a
  // pause auto-fires STOPPED after 3s - so a fresh STARTED very soon after the
  // last one is virtually always an active typing session). A gap close to or
  // above the heartbeat interval means it's just the idle heartbeat keeping
  // "active" alive, not real typing.
  private static final long HEARTBEAT_GAP_THRESHOLD  = TimeUnit.SECONDS.toMillis(6);
  private static final long ACTIVE_TIMEOUT            = TimeUnit.SECONDS.toMillis(15);

  private final Map<Long, Set<Typist>>                  typistMap;
  private final Map<Long, Set<Recipient>>                presentMap;
  private final Map<Typist, Long>                        lastStartedAt;
  private final Map<Typist, Runnable>                   timers;
  private final Map<Typist, Runnable>                    activeTimeoutTimers;
  private final Map<Long, MutableLiveData<TypingState>> notifiers;
  private final MutableLiveData<Set<Long>>              threadsNotifier;

  public TypingStatusRepository() {
    this.typistMap            = new HashMap<>();
    this.presentMap           = new HashMap<>();
    this.lastStartedAt        = new HashMap<>();
    this.timers               = new HashMap<>();
    this.activeTimeoutTimers  = new HashMap<>();
    this.notifiers            = new HashMap<>();
    this.threadsNotifier      = new MutableLiveData<>();
  }

  public synchronized void onTypingStarted(@NonNull Context context, long threadId, @NonNull Recipient author, int device) {
    if (author.isSelf()) {
      return;
    }

    Typist typist = new Typist(author, device, threadId);
    long   now    = System.currentTimeMillis();
    Long   last   = lastStartedAt.get(typist);
    lastStartedAt.put(typist, now);

    boolean isRealTyping = last != null && (now - last) < HEARTBEAT_GAP_THRESHOLD;

    Set<Typist> typists = Util.getOrDefault(typistMap, threadId, new LinkedHashSet<>());

    if (isRealTyping) {
      if (!typists.contains(typist)) {
        typists.add(typist);
        typistMap.put(threadId, typists);
      }

      Runnable timer = timers.get(typist);
      if (timer != null) {
        ThreadUtil.cancelRunnableOnMain(timer);
      }

      timer = () -> onTypingStopped(threadId, author, device, false);
      ThreadUtil.runOnMainDelayed(timer, RECIPIENT_TYPING_TIMEOUT);
      timers.put(typist, timer);
    }

    // Every STARTED (real typing or heartbeat) counts as "active". Track it in
    // presentMap too so the cat shows at least "active" even if it's not typing.
    Set<Recipient> present = Util.getOrDefault(presentMap, threadId, new LinkedHashSet<>());
    present.add(author);
    presentMap.put(threadId, present);

    Runnable activeTimeout = activeTimeoutTimers.get(typist);
    if (activeTimeout != null) {
      ThreadUtil.cancelRunnableOnMain(activeTimeout);
    }
    activeTimeout = () -> onActiveTimeout(threadId, author, device);
    ThreadUtil.runOnMainDelayed(activeTimeout, ACTIVE_TIMEOUT);
    activeTimeoutTimers.put(typist, activeTimeout);

    notifyThread(threadId, false);
  }

  /** AJ fork: no STARTED (heartbeat or typing) received within ACTIVE_TIMEOUT - treat as gone. */
  private synchronized void onActiveTimeout(long threadId, @NonNull Recipient author, int device) {
    Typist typist = new Typist(author, device, threadId);

    Set<Recipient> present = presentMap.get(threadId);
    boolean        changed = present != null && present.remove(author);
    if (present != null && present.isEmpty()) {
      presentMap.remove(threadId);
    }

    activeTimeoutTimers.remove(typist);
    lastStartedAt.remove(typist);

    if (changed) {
      notifyThread(threadId, false);
    }
  }

  public synchronized void onTypingStopped(long threadId, @NonNull Recipient author, int device, boolean isReplacedByIncomingMessage) {
    if (author.isSelf()) {
      return;
    }

    Set<Typist> typists = Util.getOrDefault(typistMap, threadId, new LinkedHashSet<>());
    Typist      typist  = new Typist(author, device, threadId);

    boolean wasTyping = typists.remove(typist);
    if (typists.isEmpty()) {
      typistMap.remove(threadId);
    }

    if (wasTyping) {
      notifyThread(threadId, isReplacedByIncomingMessage);
    }

    Runnable timer = timers.get(typist);
    if (timer != null) {
      ThreadUtil.cancelRunnableOnMain(timer);
      timers.remove(typist);
    }
  }

  public synchronized LiveData<TypingState> getTypists(long threadId) {
    MutableLiveData<TypingState> notifier = Util.getOrDefault(notifiers, threadId, new MutableLiveData<>());
    notifiers.put(threadId, notifier);
    return notifier;
  }

  public synchronized LiveData<Set<Long>> getTypingThreads() {
    return threadsNotifier;
  }

  public synchronized void stopAllTypingForThread(long threadId) {
    Set<Typist>    typists = typistMap.remove(threadId);
    Set<Recipient> present = presentMap.remove(threadId);

    if (typists != null) {
      for (Typist typist : typists) {
        Runnable timer = timers.remove(typist);
        if (timer != null) {
          ThreadUtil.cancelRunnableOnMain(timer);
        }

        Runnable activeTimeout = activeTimeoutTimers.remove(typist);
        if (activeTimeout != null) {
          ThreadUtil.cancelRunnableOnMain(activeTimeout);
        }
      }
    }

    if (typists != null || present != null) {
      notifyThread(threadId, false);
    }
  }

  public synchronized void clear() {
    TypingState empty = new TypingState(Collections.emptyList(), Collections.emptyList(), false);
    for (MutableLiveData<TypingState> notifier : notifiers.values()) {
      notifier.postValue(empty);
    }
    
    notifiers.clear();
    typistMap.clear();
    presentMap.clear();
    lastStartedAt.clear();
    timers.clear();
    activeTimeoutTimers.clear();

    threadsNotifier.postValue(Collections.emptySet());
  }

  private void notifyThread(long threadId, boolean isReplacedByIncomingMessage) {
    Set<Typist>    typists = Util.getOrDefault(typistMap, threadId, Collections.emptySet());
    Set<Recipient> present = Util.getOrDefault(presentMap, threadId, Collections.emptySet());

    Log.d(TAG, "notifyThread() threadId: " + threadId + "  typists: " + typists.size() + "  present: " + present.size() + "  isReplaced: " + isReplacedByIncomingMessage);

    MutableLiveData<TypingState> notifier = Util.getOrDefault(notifiers, threadId, new MutableLiveData<>());
    notifiers.put(threadId, notifier);

    Set<Recipient> uniqueTypists = new LinkedHashSet<>();
    for (Typist typist : typists) {
      uniqueTypists.add(typist.getAuthor());
    }

    notifier.postValue(new TypingState(new ArrayList<>(uniqueTypists), new ArrayList<>(present), isReplacedByIncomingMessage));

    Set<Long> activeThreads = Stream.of(typistMap.keySet()).filter(t -> !typistMap.get(t).isEmpty()).collect(Collectors.toSet());
    threadsNotifier.postValue(activeThreads);
  }

  public static class TypingState {
    private final List<Recipient> typists;
    private final List<Recipient> present;
    private final boolean         replacedByIncomingMessage;

    public TypingState(List<Recipient> typists, List<Recipient> present, boolean replacedByIncomingMessage) {
      this.typists                   = typists;
      this.present                   = present;
      this.replacedByIncomingMessage = replacedByIncomingMessage;
    }

    public List<Recipient> getTypists() {
      return typists;
    }

    /** AJ fork: recipients who have the chat open/foregrounded but are not currently typing. */
    public List<Recipient> getPresent() {
      return present;
    }

    public boolean isReplacedByIncomingMessage() {
      return replacedByIncomingMessage;
    }
  }

  private static class Typist {
    private final Recipient author;
    private final int       device;
    private final long      threadId;

    private Typist(@NonNull Recipient author, int device, long threadId) {
      this.author   = author;
      this.device   = device;
      this.threadId = threadId;
    }

    public Recipient getAuthor() {
      return author;
    }

    public int getDevice() {
      return device;
    }

    public long getThreadId() {
      return threadId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Typist typist = (Typist) o;

      if (device != typist.device) return false;
      if (threadId != typist.threadId) return false;
      return author.equals(typist.author);
    }

    @Override
    public int hashCode() {
      int result = author.hashCode();
      result = 31 * result + device;
      result = 31 * result + (int) (threadId ^ (threadId >>> 32));
      return result;
    }
  }
}
