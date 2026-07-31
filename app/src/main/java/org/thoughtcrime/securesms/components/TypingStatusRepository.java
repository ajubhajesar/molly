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

  private final Map<Long, Set<Typist>>                  typistMap;
  private final Map<Typist, Runnable>                   timers;

  // AJ fork: active-status presence. Driven only by explicit PRESENT / NOT_PRESENT wire
  // signals (see ActiveStatusSender / ActiveStatusSendJob) - completely separate from the
  // typing STARTED/STOPPED signals above. Deliberately no timer/timeout here: presentMap
  // only changes in direct response to a signal. If a NOT_PRESENT is lost (peer force-killed,
  // crash, etc.) this can stay stuck "present" until the next PRESENT arrives - that's an
  // accepted tradeoff for keeping this to exactly two signals with no fallback.
  private final Map<Long, Set<Recipient>>               presentMap;

  private final Map<Long, MutableLiveData<TypingState>> notifiers;
  private final MutableLiveData<Set<Long>>              threadsNotifier;

  public TypingStatusRepository() {
    this.typistMap       = new HashMap<>();
    this.timers          = new HashMap<>();
    this.presentMap      = new HashMap<>();
    this.notifiers       = new HashMap<>();
    this.threadsNotifier = new MutableLiveData<>();
  }

  public synchronized void onTypingStarted(@NonNull Context context, long threadId, @NonNull Recipient author, int device) {
    if (author.isSelf()) {
      return;
    }

    Set<Typist> typists = Util.getOrDefault(typistMap, threadId, new LinkedHashSet<>());
    Typist      typist  = new Typist(author, device, threadId);

    if (!typists.contains(typist)) {
      typists.add(typist);
      typistMap.put(threadId, typists);
      notifyThread(threadId, false);
    }

    Runnable timer = timers.get(typist);
    if (timer != null) {
      ThreadUtil.cancelRunnableOnMain(timer);
    }

    timer = () -> onTypingStopped(threadId, author, device, false);
    ThreadUtil.runOnMainDelayed(timer, RECIPIENT_TYPING_TIMEOUT);
    timers.put(typist, timer);
  }

  public synchronized void onTypingStopped(long threadId, @NonNull Recipient author, int device, boolean isReplacedByIncomingMessage) {
    if (author.isSelf()) {
      return;
    }

    Set<Typist> typists = Util.getOrDefault(typistMap, threadId, new LinkedHashSet<>());
    Typist      typist  = new Typist(author, device, threadId);

    if (typists.contains(typist)) {
      typists.remove(typist);
      notifyThread(threadId, isReplacedByIncomingMessage);
    }

    if (typists.isEmpty()) {
      typistMap.remove(threadId);
    }

    Runnable timer = timers.get(typist);
    if (timer != null) {
      ThreadUtil.cancelRunnableOnMain(timer);
      timers.remove(typist);
    }
  }

  /** AJ fork: explicit "conversation opened" signal from the peer. No timer. */
  public synchronized void onPresent(long threadId, @NonNull Recipient author) {
    if (author.isSelf()) {
      return;
    }

    Set<Recipient> present = Util.getOrDefault(presentMap, threadId, new LinkedHashSet<>());
    if (present.add(author)) {
      presentMap.put(threadId, present);
      notifyThread(threadId, false);
    }
  }

  /** AJ fork: explicit "conversation closed" signal from the peer. */
  public synchronized void onAbsent(long threadId, @NonNull Recipient author) {
    if (author.isSelf()) {
      return;
    }

    Set<Recipient> present = presentMap.get(threadId);
    if (present != null && present.remove(author)) {
      if (present.isEmpty()) {
        presentMap.remove(threadId);
      }
      notifyThread(threadId, false);
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
    timers.clear();

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

    /** AJ fork: recipients who have signaled the chat is open (PRESENT) but not currently typing. */
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
