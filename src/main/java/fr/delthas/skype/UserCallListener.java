package fr.delthas.skype;

import java.util.List;

/**
 * A listener for new messages sent to a Skype account.
 */
@FunctionalInterface
public interface UserCallListener {
    /**
     * Called when a message is sent from a user to the Skype account while it is connected.
     *
     * @param sender       The sender of the message.
     * @param message      The message sent.
     * @param callStatus
     * @param callDuration
     * @param participants
     */
    void callReceived(User sender, Message message, boolean callStatus, int callDuration, List<User> participants);
}
