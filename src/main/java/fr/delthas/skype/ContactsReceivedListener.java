package fr.delthas.skype;

import java.util.List;

/**
 * A listener for new messages sent to a Skype account.
 */
@FunctionalInterface
public interface ContactsReceivedListener {
    /**
     * Called when a message is sent from a user to the Skype account while it is connected.
     *
     * @param sender   The sender of the message.
     * @param receiver Channel on which the info was received
     * @param contacts The list of contacts received
     */
    void contactReceived(User sender, Object receiver, List<User> contacts);
}
