package fr.delthas.skype;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.*;
import java.util.stream.Collectors;

/**
 * A Skype interface to receive and send messages via a Skype account.
 * <p>
 * All IO exceptions that might be thrown by any action are catched and passed to the registered error listener and disconnect the interface
 * immediately.
 * <p>
 * <b>Note:</b> All strings passed to user/group/skype objects will have their control characters removed (0x00-0x1F and 0x7F-0x9F) except for CR and
 * LF which will be replaced with CRLF if needed.
 * <p>
 * <b>If you want to report a bug, please enable debug/logs with {@link Skype#setDebug(Path)}, before using {@link #connect()}.</b>
 */
public final class Skype {
    private static final Logger logger = Logger.getLogger("fr.delthas.skype");

    static {
        try {
            setDebug(null);
        } catch (IOException e) {
            // will not throw
        }
    }

    private final String username;
    private final String password;
    private final boolean microsoft;
    private Thread refreshThread;
    private List<UserMessageListener> userMessageListeners = new LinkedList<>();
    private List<GroupMessageListener> groupMessageListeners = new LinkedList<>();
    private List<ContactsReceivedListener> contactsReceivedListeners = new LinkedList<>();
    private List<GroupFileListener> groupFileListeners = new LinkedList<>();
    private List<UserPresenceListener> userPresenceListeners = new LinkedList<>();
    private List<GroupPropertiesListener> groupPropertiesListeners = new LinkedList<>();
    private List<UserCallListener> userCallListeners = new LinkedList<>();
    private List<GroupCallListener> groupCallListeners = new LinkedList<>();
    private ErrorListener errorListener;
    private NotifConnector notifConnector;
    private LiveConnector liveConnector;
    private WebConnector webConnector;
    private Map<String, Group> groups;
    private Set<User> contacts;
    private Map<String, User> users;
    private List<ContactRequest> contactRequests;
    private boolean connected = false;
    private boolean connecting = false;
    private volatile long expires;
    private IOException exceptionDuringConnection;

    public WebConnector getWebConnector() {
        return webConnector;
    }

    // --- Public API (except listeners add/remove methods) --- //

    /**
     * Builds a new Skype connection without connecting to anything.
     *
     * @param username The username of the Skype account to connect to.
     * @param password The password of the Skype account to connect to.
     */
    public Skype(String username, String password) {
        this.username = username;
        this.password = password;
        microsoft = username.contains("@");

        refreshThread = new RefreshThread();
        refreshThread.setName("Skype-Ping-Thread");
        refreshThread.setDaemon(true);
    }

    public static SkypeFile getSkypeFile(String name, byte[] content, SkypeFileType fileType) {
        switch (fileType) {
            case IMAGE:
                return new SkypeImage(name, content);
/*
      case VIDEO:
        return new SkypeVideo(name, content);*/

/*      case AUDIO:
        return new SkypeAudio(name, content);*/

            default:
                return new SkypeFile(name, content);
        }
    }

    /**
     * Enables or disables debug of the Skype library (globally). (By default logs are <b>disabled</b>.)
     * <p>
     * If enabled, debug information and logs will be written to a log file at the specified path. If the path is null, the debug will be disabled.
     *
     * @param path The path at which to write debugging information, or null to disable logging.
     * @throws IOException may be thrown when adding a file handler to the logger
     */
    public static void setDebug(Path path) throws IOException {
        if (path == null) {
            logger.setLevel(Level.OFF);
        } else {
            logger.setLevel(Level.ALL);
            logger.setUseParentHandlers(false);
            for (Handler handler : logger.getHandlers()) {
                logger.removeHandler(handler);
                handler.close();
            }
            FileHandler fh = new FileHandler(path.toString(), false);
            fh.setFormatter(new SimpleFormatter());
            logger.addHandler(fh);
        }
    }

    /**
     * Calls {@code connect(Presence.CONNECTED)}.
     *
     * @throws IOException          If an error is thrown while connecting.
     * @throws InterruptedException If the connection is interrupted.
     * @see #connect(Presence)
     */
    public void connect() throws IOException, InterruptedException {
        connect(Presence.ONLINE);
    }

    private void tryConnect(Presence presence) throws IOException, InterruptedException {
        if (connecting || connected) {
            return;
        }
        connected = true;
        connecting = true;

        logger.fine("Connecting to Skype");

        reset();

        long expires = Long.MAX_VALUE;

        try {
            if (microsoft) {
                // webConnector and notifConnector depend on liveConnector
                expires = liveConnector.refreshTokens();
            }

            // notifConnector depends on webConnector
            expires = Long.min(expires, webConnector.refreshTokens(liveConnector.getSkypeToken()));

            if (presence == null) {
                Presence userCurrentStatus = webConnector.getUserCurrentStatus();
                logger.info("Current user status: " + userCurrentStatus);
                getSelf().setPresence(userCurrentStatus, false);
            } else {
                getSelf().setPresence(presence, false);
            }

            // will block until connected
            expires = Long.min(expires, notifConnector.connect(liveConnector.getLoginToken(), liveConnector.getLiveToken()));
        } catch (IOException e) {
            throw new IOException("Error thrown during connection. Check your credentials?", e);
        }

        this.expires = expires;

        connecting = false;

        if (exceptionDuringConnection != null) {
            // an exception has been thrown during connection
            throw new IOException("Error thrown during connection. Check your credentials?", exceptionDuringConnection);
        }

        refreshThread.start();
    }

    /**
     * Connects the Skype interface. Will block until connected.
     *
     * @param presence The initial presence of the Skype account after connection. Cannot be {@link Presence#OFFLINE}.
     * @throws IOException          If an error is thrown while connecting.
     * @throws InterruptedException If the connection is interrupted.
     */
    public void connect(Presence presence) throws IOException, InterruptedException {
        if (presence == Presence.OFFLINE) {
            throw new IllegalArgumentException("Presence can't be set to offline. Use HIDDEN if you want to connect without being visible.");
        }
        int counter = 0;
        while (counter < 3) {
            try {
                counter++;
                tryConnect(presence);
                break;
            } catch (Exception e) {
                if (counter < 3) {
                    logger.warning("An error occurred trying to connect (try number: " + String.valueOf(counter) + "), " + e.getMessage());
                    connected = false;
                    connecting = false;
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Disconnects the Skype interface.
     * <p>
     * All User, Group, and ContactRequest objects will remain valid for the next connections. Make sure to reconnect (start) before triggering actions
     * from these, however, or they will throw an IllegalStateException.
     */
    public void disconnect() {
        if (connecting || !connected) {
            return;
        }
        connected = false;

        logger.fine("Disconnecting from Skype");

        refreshThread.interrupt();
        notifConnector.disconnect();
        for (Map.Entry<String, User> user : users.entrySet()) {
            user.getValue().setPresence(Presence.OFFLINE, false);
        }
        reset();
    }

    /**
     * @return The current list of contact requests to this Skype account (snapshot, won't be updated).
     */
    public List<ContactRequest> getContactRequests() {
        ensureConnected();
        return Collections.unmodifiableList(new ArrayList<>(contactRequests));
    }

    /**
     * @return The groups (or threads) the Skype account is currently in (as a snapshot: the list won't be updated).
     */
    public List<Group> getGroups() {
        ensureConnected();
        return Collections.unmodifiableList(new ArrayList<>(groups.values()));
    }

    /**
     * @return The User object representing the Skype account.
     */
    public User getSelf() {
        ensureConnected();
        return getUser(username);
    }

    /**
     * @return The current list of contacts of the account (snapshot, won't be updated).
     */
    public List<User> getContacts() {
        ensureConnected();
        return Collections.unmodifiableList(new ArrayList<>(contacts));
    }

    /**
     * Changes the presence of the Skype account.
     * <p>
     * All presence values are valid except {@link Presence#OFFLINE} : to disconnect, use {@link #disconnect()}.
     *
     * @param presence The new presence of the Skype account.
     * @see Presence
     */
    public void changePresence(Presence presence) {
        if (presence == Presence.OFFLINE) {
            throw new IllegalArgumentException("Presence can't be set to offline. Use HIDDEN if you want to connect without being visible.");
        }
        ensureConnected();
        try {
            logger.finer("Changing presence to " + presence);
            notifConnector.changePresence(presence);
        } catch (IOException e) {
            error(e);
        }
    }

    /**
     * @return true if the Skype interface is connected.
     */
    public boolean isConnected() {
        return connected;
    }

    // --- Package-private methods --- //

    User getUser(String username) {
        User user = users.computeIfAbsent(username, u -> new User(this, u));
        return user;
    }

    Group getGroup(String id) {
        Group group = groups.computeIfAbsent(id, i -> new Group(this, i));
        return group;
    }

    void addContact(String username) {
        logger.finest("Adding contact " + username);
        contacts.add(getUser(username));
    }

    void error(IOException e) {
        logger.log(Level.SEVERE, "Error thrown", e);
        if (errorListener != null) {
            errorListener.error(e);
        } else {
            logger.severe("No error listener set!!!");
        }
        if (connecting) {
            exceptionDuringConnection = e;
        } else {
            disconnect();

            logger.log(Level.INFO, "trying to reconnect straightaway");
            try {
                this.connect(Presence.ONLINE);
            } catch (IOException | InterruptedException e1) {
                logger.log(Level.SEVERE, "Error thrown while trying to reconnect", e1);
            }
        }
    }

    private void ensureConnected() throws IllegalStateException {
        if (!connected) {
            logger.log(Level.SEVERE, "Was not connected while trying to ensure connected, attempting to connect...");
            try {
                connect();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Exception while reconnecting", e);
                errorListener.error(e);
            } catch (InterruptedException e) {
                logger.log(Level.SEVERE, "Exception while reconnecting", e);
                e.printStackTrace();
            }
        }
    }

    private void reset() {
        logger.finest("Resetting the Skype object");
        liveConnector = new LiveConnector(username, password);
        notifConnector = new NotifConnector(this, username, password);
        webConnector = new WebConnector(this, username, password);
        groups = new HashMap<>();
        contacts = new HashSet<>();
        users = new HashMap<>();
        contactRequests = new LinkedList<>();
        exceptionDuringConnection = null;

        refreshThread = new RefreshThread();
        refreshThread.setName("Skype-Ping-Thread");
        refreshThread.setDaemon(true);
    }

    // --- Package-private methods that simply call the web connector --- //

    void block(User user) {
        ensureConnected();
        try {
            logger.finer("Blocking user: " + user);
            webConnector.block(user);
        } catch (IOException e) {
            error(e);
        }
    }

    void unblock(User user) {
        ensureConnected();
        try {
            logger.finer("Unblocking user: " + user);
            webConnector.unblock(user);
        } catch (IOException e) {
            error(e);
        }
    }

    void sendContactRequest(User user, String greeting) {
        ensureConnected();
        try {
            logger.finer("Sending user: " + user + " a contact request: greeting:" + greeting);
            webConnector.sendContactRequest(user, greeting);
        } catch (IOException e) {
            error(e);
        }
    }

    void removeFromContacts(User user) {
        ensureConnected();
        try {
            logger.finer("Removing user: " + user + " from contacts");
            webConnector.removeFromContacts(user);
            contacts.remove(user);
        } catch (IOException e) {
            error(e);
        }
    }

    public byte[] getAvatar(User user) {
        ensureConnected();
        try {
            return webConnector.getAvatar(user);
        } catch (IOException e) {
            error(e);
            return null;
        }
    }

    /**
     * @param group
     * @param fileName
     * @param fileBytes
     * @param image
     * @see #sendFile(Group, SkypeFile)
     * @deprecated , left for backward compatibility
     */
    public void sendFile(Group group, String fileName, byte[] fileBytes, boolean image) {
        SkypeFile skypeFile = getSkypeFile(fileName, fileBytes, image ? SkypeFileType.IMAGE : SkypeFileType.PLAIN_FILE);
        sendFile(group, skypeFile);
    }

    public void sendFile(Group group, SkypeFile skypeFile) {
        ensureConnected();
        try {
            webConnector.sendFile(group, skypeFile);
        } catch (IOException e) {
            error(e);
            return;
        }
    }

    byte[] getFile(String url) {
        ensureConnected();
        try {
            return webConnector.getFileAsBytes(url);
        } catch (IOException e) {
            error(e);
            return null;
        }
    }

    void sendGroupMessage(Group group, String message, boolean raw, String messageType, String contentTypeHeader) {
        ensureConnected();
        try {
            logger.finer("Sending group: " + group + " message: " + message);
            notifConnector.sendGroupMessage(group, message, raw, messageType, contentTypeHeader);
        } catch (IOException e) {
            error(e);
        }
    }

    void updateUser(User user) {
        if (!users.containsKey(user.getUsername())) {
            try {
                logger.finest("Updating user info: " + user);
                webConnector.updateUser(user);
            } catch (IOException e) {
                error(e);
            }
        }
    }

    void acceptContactRequest(ContactRequest contactRequest) {
        ensureConnected();
        try {
            logger.finer("Accepting contact request: " + contactRequest);
            webConnector.acceptContactRequest(contactRequest);
            contactRequests.remove(contactRequest);
        } catch (IOException e) {
            error(e);
        }
    }

    void declineContactRequest(ContactRequest contactRequest) {
        ensureConnected();
        try {
            logger.finer("Declining contact request: " + contactRequest);
            webConnector.declineContactRequest(contactRequest);
            contactRequests.remove(contactRequest);
        } catch (IOException e) {
            error(e);
        }
    }

    // --- Package-private methods that simply call the notification connector --- //

    void sendUserMessage(User user, String message) {
        ensureConnected();
        try {
            logger.finer("Sending user: " + user + " message: " + message);
            notifConnector.sendUserMessage(user, message);
        } catch (IOException e) {
            error(e);
        }
    }

    void sendUserCard(User user, JSONObject card) {
        ensureConnected();
        logger.finer("Sending user: " + user + " card: " + card);
        try {
            notifConnector.sendUserCard(user, card);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void userMessageReceived(User sender, String message, Date date) {
        updateUser(sender);
        logger.finer("Received message: " + message + " from user: " + sender);
        for (UserMessageListener listener : userMessageListeners) {
            listener.messageReceived(sender, message, date);
        }
    }

    void addUserToGroup(User user, Role role, Group group) {
        ensureConnected();
        try {
            logger.finer("Adding user: " + user + " to group: " + group + " with role: " + role);
            notifConnector.addUserToGroup(user, role, group);
        } catch (IOException e) {
            error(e);
        }
    }

    void removeUserFromGroup(User user, Group group) {
        ensureConnected();
        try {
            logger.finer("Removing user: " + user + " from group: " + group);
            notifConnector.removeUserFromGroup(user, group);
        } catch (IOException e) {
            error(e);
        }
    }

    void changeUserRole(User user, Role role, Group group) {
        ensureConnected();
        try {
            logger.finer("Changing user: " + user + " from group: " + group + " role to: " + role);
            notifConnector.changeUserRole(user, role, group);
        } catch (IOException e) {
            error(e);
        }
    }

    void changeGroupTopic(Group group, String topic) {
        ensureConnected();
        try {
            logger.finer("Setting group: " + group + " topic to: " + topic);
            notifConnector.changeGroupTopic(group, topic);
        } catch (IOException e) {
            error(e);
        }
    }

    // --- Listeners call methods --- //

    void groupMessageReceived(Group group, User sender, String message, Date date) {
        logger.finer("Received group message: " + message + " from user: " + sender + " in group: " + group);
        for (GroupMessageListener listener : groupMessageListeners) {
            listener.messageReceived(group, sender, message, date);
        }
    }

    void groupFileReceived(Group group, User sender, SkypeFile skypeFile) {
        logger.finer("Received file (type: " + skypeFile.getEnumType().getName() + ") from user: " + sender + " in group: " + group);
        for (GroupFileListener listener : groupFileListeners) {
            listener.fileReceived(group, sender, skypeFile);
        }
    }

    void contactMessageReceived(User sender, Object receiver, List<User> contacts) {
        logger.finer("Received contacts from: " + sender.getUsername() + " Contacts: " + contacts + " On private channel: " + (receiver instanceof Group));
        for (ContactsReceivedListener listener : contactsReceivedListeners) {
            listener.contactReceived(sender, receiver, contacts);
        }
    }

    // Call listeners
    void userCallReceived(User sender, User receiver, boolean wasCallEnded, boolean wasCallMissed, Long messageId, Date date, int callDuration, List<User> participants) {
        updateUser(sender);
        logger.finer("Received call: " + " from user: " + sender);
        for (UserCallListener listener : userCallListeners) {
            listener.callReceived(sender, receiver, wasCallEnded, wasCallMissed, messageId, date, callDuration, participants);
        }
    }

    void userPresenceChanged(User user, Presence oldPresence, Presence presence) {
        logger.finer("User: " + user + " changed presence from: " + oldPresence + " to: " + presence);
        for (UserPresenceListener listener : userPresenceListeners) {
            listener.presenceChanged(user, oldPresence, presence);
        }
    }

    void usersAddedToGroup(List<User> users, Group group) {
        logger.finer("Users: " + users.stream().map(User::getUsername).collect(Collectors.joining(", ")) + " added to group: " + group);
        for (GroupPropertiesListener listener : groupPropertiesListeners) {
            listener.usersAdded(group, users);
        }
    }

    void usersRemovedFromGroup(List<User> users, Group group) {
        logger.finer("Users: " + users.stream().map(User::getUsername).collect(Collectors.joining(", ")) + " removed from group: " + group);
        for (GroupPropertiesListener listener : groupPropertiesListeners) {
            listener.usersRemoved(group, users);
        }
    }

    void usersRolesChanged(Group group, List<Pair<User, Role>> newRoles) {
        logger.finer(
                "User roles changed: " + newRoles.stream().map(p -> p.getFirst().getUsername() + ":" + p.getSecond()).collect(Collectors.joining(", ")));
        for (GroupPropertiesListener listener : groupPropertiesListeners) {
            listener.usersRolesChanged(group, newRoles);
        }
    }

    void groupTopicChanged(Group group, String topic) {
        logger.finer("Group: " + group + " topic changed to: " + topic);
        for (GroupPropertiesListener listener : groupPropertiesListeners) {
            listener.topicChanged(group, topic);
        }
    }

    void groupCallReceived(User sender, Group receiver, boolean wasCallEnded, boolean wasCallMissed, Long messageId, Date date, int callDuration, List<User> participants) {
        logger.finer("Received group call: " + " from user: " + sender + " in group: " + receiver);
        for (GroupCallListener listener : groupCallListeners) {
            listener.callReceived(sender, receiver, wasCallEnded, wasCallMissed, messageId, date, callDuration, participants);
        }
    }

    /**
     * Adds a file message listener.
     *
     * @param groupFileListener The group file listener to add.
     */
    public void addGroupFileListener(GroupFileListener groupFileListener) {
        groupFileListeners.add(groupFileListener);
    }

    // --- Listeners change methods ---

    /**
     * Adds a user message listener.
     *
     * @param userMessageListener The user message listener to add.
     */
    public void addUserMessageListener(UserMessageListener userMessageListener) {
        userMessageListeners.add(userMessageListener);
    }

    /**
     * Removes a user message listener.
     *
     * @param userMessageListener The user message listener to remove.
     */
    public void removeUserMessageListener(UserMessageListener userMessageListener) {
        userMessageListeners.remove(userMessageListener);
    }

    /**
     * Adds a group message listener.
     *
     * @param groupMessageListener The group message listener to add.
     */
    public void addGroupMessageListener(GroupMessageListener groupMessageListener) {
        groupMessageListeners.add(groupMessageListener);
    }

    /**
     * Removes a group call listener.
     *
     * @param groupCallListener The user call listener to remove.
     */
    public void removeGroupCallListener(GroupCallListener groupCallListener) {
        groupCallListeners.remove(groupCallListener);
    }

    /**
     * Removes a group message listener.
     *
     * @param groupMessageListener The group message listener to remove.
     */
    public void removeGroupMessageListener(GroupMessageListener groupMessageListener) {
        groupMessageListeners.remove(groupMessageListener);
    }

    /**
     * Adds a contact message listener.
     *
     * @param contactReceivedListener The user message listener to add.
     */
    public void addContactReceivedListener(ContactsReceivedListener contactReceivedListener) {
        contactsReceivedListeners.add(contactReceivedListener);
    }

    /**
     * Removes a contact message listener.
     *
     * @param contactReceivedListener The user message listener to add.
     */
    public void removeContactReceivedListener(ContactsReceivedListener contactReceivedListener) {
        contactsReceivedListeners.add(contactReceivedListener);
    }

    /**
     * Adds a user presence listener.
     *
     * @param userPresenceListener The user presence listener to add.
     */
    public void addUserPresenceListener(UserPresenceListener userPresenceListener) {
        userPresenceListeners.add(userPresenceListener);
    }

    /**
     * Removes a user presence listener.
     *
     * @param userPresenceListener The user presence listener to remove.
     */
    public void removeUserPresenceListener(UserPresenceListener userPresenceListener) {
        userPresenceListeners.remove(userPresenceListener);
    }

    /**
     * Adds a group properties listener.
     *
     * @param groupPropertiesListener The group properties listener to add.
     */
    public void addGroupPropertiesListener(GroupPropertiesListener groupPropertiesListener) {
        groupPropertiesListeners.add(groupPropertiesListener);
    }

    /**
     * Removes a group properties listener.
     *
     * @param groupPropertiesListener The group properties listener to remove.
     */
    public void removeGroupPropertiesListener(GroupPropertiesListener groupPropertiesListener) {
        groupPropertiesListeners.remove(groupPropertiesListener);
    }

    /**
     * Adds a user call listener.
     *
     * @param userCallListener The user call listener to add.
     */
    public void addUserCallListener(UserCallListener userCallListener) {
        userCallListeners.add(userCallListener);
    }

    /**
     * Removes a user call listener.
     *
     * @param userCallListener The user call listener to add.
     */
    public void removeUserCallListener(UserCallListener userCallListener) {
        userCallListeners.remove(userCallListener);
    }


    /**
     * Adds a group call listener.
     *
     * @param groupCallListener The user call listener to add.
     */
    public void addGroupCallListener(GroupCallListener groupCallListener) {
        groupCallListeners.add(groupCallListener);
    }

    private class RefreshThread extends Thread {
        @Override
        public void run() {
            long expires = System.nanoTime() + (Skype.this.expires - System.nanoTime()) * 3 / 4;
            while (!Thread.interrupted()) {
                try {
                    if (System.nanoTime() >= expires) {
                        try {
                            logger.finer("Refreshing tokens");
                            if (microsoft) {
                                expires = liveConnector.refreshTokens();
                            }
                            expires = Long.min(expires, webConnector.refreshTokens(liveConnector.getSkypeToken()));
                            expires = Long.min(expires, notifConnector.refreshTokens(liveConnector.getLoginToken(), liveConnector.getLiveToken()));
                        } catch (IOException e) {
                            logger.log(Level.INFO, "Error while refreshing tokens", e);
                        }
                        expires = System.nanoTime() + (expires - System.nanoTime()) * 3 / 4;
                    }
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /**
     * Sets an error listener for the Skype interface.
     *
     * @param errorListener The error listener to set.
     */
    public void setErrorListener(ErrorListener errorListener) {
        this.errorListener = errorListener;
    }
}
