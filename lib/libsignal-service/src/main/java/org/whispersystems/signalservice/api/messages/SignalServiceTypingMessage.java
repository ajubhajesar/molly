package org.whispersystems.signalservice.api.messages;


import java.util.Optional;

public class SignalServiceTypingMessage {

  public enum Action {
    UNKNOWN, STARTED, STOPPED, PRESENT, NOT_PRESENT, REQUEST_PRESENCE,
    // AJ fork: live-typing consent handshake + draft-sharing. See SignalService.proto for the
    // full contract - REQUEST/ACCEPT/DECLINE/STOP are the handshake, UPDATE carries the text,
    // RESYNC_REQUEST/RESYNC_ACTIVE/RESYNC_NONE recover a dropped ACCEPT/STOP after a reconnect.
    LIVE_TEXT_REQUEST, LIVE_TEXT_ACCEPT, LIVE_TEXT_DECLINE, LIVE_TEXT_STOP, LIVE_TEXT_UPDATE,
    LIVE_TEXT_RESYNC_REQUEST, LIVE_TEXT_RESYNC_ACTIVE, LIVE_TEXT_RESYNC_NONE
  }

  private final Action           action;
  private final long             timestamp;
  private final Optional<byte[]> groupId;
  private final Optional<String> liveText;

  public SignalServiceTypingMessage(Action action, long timestamp, Optional<byte[]> groupId) {
    this(action, timestamp, groupId, Optional.empty());
  }

  /** AJ fork: overload carrying the live draft buffer, used only for LIVE_TEXT_UPDATE. */
  public SignalServiceTypingMessage(Action action, long timestamp, Optional<byte[]> groupId, Optional<String> liveText) {
    this.action    = action;
    this.timestamp = timestamp;
    this.groupId   = groupId;
    this.liveText  = liveText;
  }

  public Action getAction() {
    return action;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public Optional<byte[]> getGroupId() {
    return groupId;
  }

  public Optional<String> getLiveText() {
    return liveText;
  }

  public boolean isTypingStarted() {
    return action == Action.STARTED;
  }

  public boolean isTypingStopped() {
    return action == Action.STOPPED;
  }

  public boolean isPresent() {
    return action == Action.PRESENT;
  }

  public boolean isNotPresent() {
    return action == Action.NOT_PRESENT;
  }

  public boolean isRequestPresence() {
    return action == Action.REQUEST_PRESENCE;
  }

  public boolean isLiveTextRequest() {
    return action == Action.LIVE_TEXT_REQUEST;
  }

  public boolean isLiveTextAccept() {
    return action == Action.LIVE_TEXT_ACCEPT;
  }

  public boolean isLiveTextDecline() {
    return action == Action.LIVE_TEXT_DECLINE;
  }

  public boolean isLiveTextStop() {
    return action == Action.LIVE_TEXT_STOP;
  }

  public boolean isLiveTextUpdate() {
    return action == Action.LIVE_TEXT_UPDATE;
  }

  public boolean isLiveTextResyncRequest() {
    return action == Action.LIVE_TEXT_RESYNC_REQUEST;
  }

  public boolean isLiveTextResyncActive() {
    return action == Action.LIVE_TEXT_RESYNC_ACTIVE;
  }

  public boolean isLiveTextResyncNone() {
    return action == Action.LIVE_TEXT_RESYNC_NONE;
  }
}
