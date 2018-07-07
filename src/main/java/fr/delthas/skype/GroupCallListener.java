package fr.delthas.skype;

import java.util.List;

/**
 * A listener for new messages sent to a Skype account.
 */
@FunctionalInterface
public interface GroupCallListener {
    /**
     * Called when a message is sent from a user to a group the Skype account is in while it is connected.
     *
     * @param group        The group in which the message has been sent.
     * @param sender       The sender of the message.
     * @param message      The message sent.
     * @param callStatus
     * @param callDuration
     * @param participants
     */
    void callReceived(Group group, User sender, Message message, boolean callStatus, int callDuration, List<User> participants);
}
