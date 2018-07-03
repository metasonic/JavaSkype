package fr.delthas.skype;

public class Message {
  private User sender;
  private Object receiver;
  private String message;
  private Long messageId;
  private String messageType;
  private String originalArrivalTime;

  public Message(User sender, Object receiver, String message, Long messageId, String messageType, String ordinalArrivalTime) {
    this.sender = sender;
    this.receiver = receiver;
    this.message = message;
    this.messageId = messageId;
    this.messageType = messageType;
    this.originalArrivalTime = ordinalArrivalTime;
  }

  public User getSender() {
    return sender;
  }

  public Object getReceiver() {
    return receiver;
  }

  public String getMessage() {
    return message;
  }

  public Long getMessageId() {
    return messageId;
  }

  public String getMessageType() {
    return messageType;
  }

  public String getOriginalArrivalTime() {
    return originalArrivalTime;
  }

  @Override
  public String toString() {
    return message;
  }
}
