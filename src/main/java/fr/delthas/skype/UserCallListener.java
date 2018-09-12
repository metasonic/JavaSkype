package fr.delthas.skype;

import java.util.Date;
import java.util.List;

/**
 * A listener for new messages sent to a Skype account.
 */
@FunctionalInterface
public interface UserCallListener {
    /**
     * Called when a message is sent from a user to the Skype account while it is connected.
     *  @param sender       The sender of the message.
     * @param receiver     The receiver of the message.
     * @param wasCallEnded
     * @param wasCallMissed
     * @param callId
     * @param date
     * @param callDuration
     * @param participants
     */
    void callReceived(User sender,
                      User receiver,
                      boolean wasCallEnded,
                      boolean wasCallMissed,
                      Long callId,
                      Date date,
                      int callDuration,
                      List<User> participants);
}
