package org.whispersystems.signalservice.api.messages;


import java.util.Optional;

/**
 * AJ fork: fully independent of SignalServiceTypingMessage. Represents whether
 * the conversation screen is foregrounded/resumed ("active"). Has no relationship
 * to typing - typing starting or stopping never affects this value.
 */
public class SignalServicePresenceMessage {

  public enum Action {
    UNKNOWN, ACTIVE, INACTIVE
  }

  private final Action           action;
  private final long             timestamp;
  private final Optional<byte[]> groupId;

  public SignalServicePresenceMessage(Action action, long timestamp, Optional<byte[]> groupId) {
    this.action    = action;
    this.timestamp = timestamp;
    this.groupId   = groupId;
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

  public boolean isActive() {
    return action == Action.ACTIVE;
  }

  public boolean isInactive() {
    return action == Action.INACTIVE;
  }
}
