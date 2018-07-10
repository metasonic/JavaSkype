package fr.delthas.skype;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

class WebConnector {
    private static final Logger logger = Logger.getLogger("fr.delthas.skype.web");
    private static final String SERVER_HOSTNAME = "https://api.skype.com";
    private final Skype skype;
    private final String username, password;
    private String skypeToken;
    private boolean updated = false;

    public WebConnector(Skype skype, String username, String password) {
        this.skype = skype;
        this.username = username;
        this.password = password;
    }

    private static String getPlaintext(String string) {
        if (string == null) {
            return null;
        }
        return Jsoup.parseBodyFragment(string).text();
    }

    public synchronized long refreshTokens(String token) throws IOException {
        logger.finer("Refreshing tokens");
        long expire = generateToken(token);
        updateContacts();
        return expire;
    }

    public void block(User user) throws IOException {
        sendRequest(Method.PUT, "/users/self/contacts/" + user.getUsername() + "/block", "reporterIp", "127.0.0.1");
    }

    public void unblock(User user) throws IOException {
        sendRequest(Method.PUT, "/users/self/contacts/" + user.getUsername() + "/unblock");
    }

    public void sendContactRequest(User user, String greeting) throws IOException {
        HashMap<String, String> contactRequestPayload = new HashMap<>();
        contactRequestPayload.put("mri", user.getUsername().contains("live") ? user.getUsername() : "8:" + user.getUsername());
        contactRequestPayload.put("greeting", greeting);

        JSONObject json = new JSONObject(contactRequestPayload);
        sendJsonRequest(Method.POST, "/contacts/v2/users/" + skype.getSelf().getLiveUsername() + "/contacts", json);
    }

    public void acceptContactRequest(ContactRequest contactRequest) throws IOException {
        sendRequest(Method.PUT, "/users/self/contacts/auth-request/" + contactRequest.getUser().getUsername() + "/accept");
    }

    public void declineContactRequest(ContactRequest contactRequest) throws IOException {
        sendRequest(Method.PUT, "/users/self/contacts/auth-request/" + contactRequest.getUser().getUsername() + "/decline");
    }

    public void removeFromContacts(User user) throws IOException {
        sendRequest(Method.DELETE, "/users/self/contacts/" + user.getUsername());
    }

    public byte[] getAvatar(User user) throws IOException {
        return sendRequest(Method.GET, user.getAvatarUrl(), true).bodyAsBytes();
    }

    public void updateUser(User user) throws IOException {
        String reponse = sendRequest(Method.GET, "/users/" + user.getUsername() + "/profile/public", "clientVersion", "0/7.12.0.101/").body();
        JSONObject userJSON = new JSONObject(reponse);
        userJSON.put("username", user.getUsername());
        updateUser(userJSON, false);
    }

    private void updateContacts() throws IOException {
        if (updated) {
            return;
        }
        updated = true;
        String selfResponse = sendRequest(Method.GET, "/users/self/profile").body();
        JSONObject selfJSON = new JSONObject(selfResponse);

        User loggedUser = updateUser(selfJSON, false, username);

        JSONArray financialStatsResponse = getFinancialStats(skypeToken, loggedUser.getLiveUsername());
        if (financialStatsResponse != null && financialStatsResponse.length() > 0) {
            JSONObject financialData = financialStatsResponse.getJSONObject(0);
            loggedUser.setBalance(financialData.optInt("balance"));
            loggedUser.setBalanceFormatted(financialData.optString("balanceFormatted"));
        }


        String profilesResponse =
                sendRequest(Method.GET, "https://contacts.skype.com/contacts/v2/users/" + loggedUser.getLiveUsername() + "/contacts", true).body();

        try {
            JSONObject json = new JSONObject(profilesResponse);
            if (json.optString("message", null) != null) {
                throw new ParseException("Error while parsing contacts response: " + json.optString("message"));
            }
            JSONArray profilesJSON = json.getJSONArray("contacts");
            for (int i = 0; i < profilesJSON.length(); i++) {
                User user = updateUser(profilesJSON.getJSONObject(i), true);
                if (user != null && !user.getUsername().equalsIgnoreCase("echo123")) {
                    skype.addContact(user.getUsername());
                }
            }
        } catch (JSONException e) {
            throw new ParseException(e);
        }
    }

    private User updateUser(JSONObject userJSON, boolean newContactType) throws ParseException {
        return updateUser(userJSON, newContactType, null);
    }

    private User updateUser(JSONObject userJSON, boolean newContactType, String username) throws ParseException {
        String userUsername;
        String userFirstName = null;
        String userLastName = null;
        String userMood = null;
        String userCountry = null;
        String userCity = null;
        String userDisplayName = null;
        String userAvatarUrl = null;
        String userPhoneHome = null;
        String userPhoneMobile = null;
        String userPhoneOffice = null;
        try {
            if (!newContactType) {
                userUsername = userJSON.optString("username");
                userFirstName = userJSON.optString("firstname", null);
                userLastName = userJSON.optString("lastname", null);
                userMood = userJSON.optString("mood", null);
                userCountry = userJSON.optString("country", null);
                userCity = userJSON.optString("city", null);
                userDisplayName = userJSON.optString("displayname", null);
                userAvatarUrl = userJSON.optString("avatarUrl");
                userPhoneHome = userJSON.optString("phoneHome");
                userPhoneMobile = userJSON.optString("phoneMobile");
                userPhoneOffice = userJSON.optString("phoneOffice");
            } else {
                if (userJSON.optBoolean("blocked", false)) {
                    return null;
                }
                if (!userJSON.optBoolean("authorized", false)) {
                    return null;
                }
                if (userJSON.optBoolean("suggested", false)) {
                    return null;
                }

                String mri = userJSON.getString("mri");
                int senderBegin = mri.indexOf(':');
                int network;
                try {
                    network = Integer.parseInt(mri.substring(0, senderBegin));
                } catch (NumberFormatException e) {
                    logger.warning("Error while parsing entity " + mri + ": unknown network format:" + mri);
                    return null;
                }
                if (network != 8 || network != 28) {
                    return null;
                }
                userUsername = mri.substring(senderBegin + 1);
                userDisplayName = userJSON.optString("display_name", null);
                JSONObject profileJSON = userJSON.getJSONObject("profile");

                if (profileJSON.has("name")) {
                    JSONObject nameJSON = profileJSON.getJSONObject("name");
                    userFirstName = nameJSON.optString("first", null);
                    userLastName = nameJSON.optString("surname", null);
                }

                userMood = profileJSON.optString("mood", null);
                if (profileJSON.has("locations")) {
                    JSONObject locationJSON = profileJSON.optJSONArray("locations").optJSONObject(0);
                    if (locationJSON != null) {
                        userCountry = locationJSON.optString("country", null);
                        userCity = locationJSON.optString("city", null);
                    }
                }
                userAvatarUrl = profileJSON.optString("avatar_url");
            }
        } catch (JSONException e) {
            throw new ParseException(e);
        }
        User user = skype.getUser(username != null ? username : userUsername);
        user.setCity(getPlaintext(userCity));
        user.setCountry(getPlaintext(userCountry));
        user.setDisplayName(getPlaintext(userDisplayName));
        user.setFirstName(getPlaintext(userFirstName));
        user.setLastName(getPlaintext(userLastName));
        user.setMood(getPlaintext(userMood));
        user.setAvatarUrl(userAvatarUrl);
        user.setLiveUsername(userUsername);
        user.setPhoneHome(userPhoneHome);
        user.setPhoneMobile(userPhoneMobile);
        user.setPhoneOffice(userPhoneOffice);
        return user;
    }

    private long generateToken(String token) throws IOException {
        String response;
        if (token == null) {
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                // md5 will always be available according to javadoc
                throw new RuntimeException(e);
            }
            byte[] md5hash = md.digest(String.format("%s\nskyper\n%s", username, password).getBytes(StandardCharsets.UTF_8));
            String base64hash = Base64.getEncoder().encodeToString(md5hash);
            logger.finest("Getting Skype token");
            response = sendRequest(Method.POST, "/login/skypetoken", "scopes", "client", "clientVersion", "0/7.12.0.101/", "username", username,
                    "passwordHash", base64hash).body();
        } else {
            logger.finest("Getting Microsoft token");
            response = sendRequest(Method.POST, "/rps/skypetoken", "scopes", "client", "clientVersion", "0/7.12.0.101/", "access_token", token, "partner", "999").body();
        }
        try {
            JSONObject jsonResponse = new JSONObject(response);
            if (!jsonResponse.has("skypetoken")) {
                if (jsonResponse.has("status") && jsonResponse.getJSONObject("status").has("text")) {
                    IOException e = new IOException("Error while connecting to Skype: " + jsonResponse.getJSONObject("status").getString("text"));
                    logger.log(Level.SEVERE, "", e);
                    throw e;
                } else {
                    IOException e = new IOException("Unknown error while connecting to Skype: " + jsonResponse);
                    logger.log(Level.SEVERE, "", e);
                    throw e;
                }
            }
            skypeToken = jsonResponse.getString("skypetoken");
            return System.nanoTime() + jsonResponse.getInt("expiresIn") * 1000000000L;
        } catch (JSONException e_) {
            ParseException e = new ParseException("Error while parsing skypetoken response: " + response, e_);
            logger.log(Level.SEVERE, "", e);
            throw e;
        }
    }

    private Response sendRequest(Method method, String apiPath, boolean absoluteApiPath, String... keyval) throws IOException {
        String url;
        if (apiPath.contains("contacts")) {
            url = absoluteApiPath ? apiPath : "https://contacts.skype.com" + apiPath;
        } else {
            url = absoluteApiPath ? apiPath : SERVER_HOSTNAME + apiPath;
        }
        Connection conn = Jsoup.connect(url).maxBodySize(100 * 1024 * 1024).timeout(10000).method(method).ignoreContentType(true).ignoreHttpErrors(true);
        logger.finest("Sending " + method + " request at " + url);
        if (skypeToken != null) {
            conn.header("X-Skypetoken", skypeToken);
            conn.header("Accept", "application/json");
        } else {
            logger.fine("No token sent for the request at: " + url);
        }
        conn.data(keyval);
        return conn.execute();
    }

    private Response sendRequest(Method method, String apiPath, String... keyval) throws IOException {
        return sendRequest(method, apiPath, false, keyval);
    }

    private Response sendJsonRequest(Method method, String apiPath, boolean absoluteApiPath, JSONObject json) throws IOException {
        String url;
        if (apiPath.contains("contacts")) {
            url = absoluteApiPath ? apiPath : "https://contacts.skype.com" + apiPath;
        } else {
            url = absoluteApiPath ? apiPath : SERVER_HOSTNAME + apiPath;
        }
        Connection conn = Jsoup.connect(url).maxBodySize(100 * 1024 * 1024).timeout(10000).method(method).ignoreContentType(true).ignoreHttpErrors(true);
        logger.finest("Sending " + method + " request at " + url);
        if (skypeToken != null) {
            conn.header("X-Skypetoken", skypeToken);
            conn.header("Accept", "application/json");
        } else {
            logger.fine("No token sent for the request at: " + url);
        }
        conn.requestBody(json.toString());
        return conn.execute();
    }

    private Response sendJsonRequest(Method method, String apiPath, JSONObject json) throws IOException {
        return sendJsonRequest(method, apiPath, false, json);
    }

    private JSONObject findEndpoint(String skypeToken) {
        for (int i = 1; i <= 5; i++) {
            try {
                logger.info("Finding other endpoints... Attempt " + Integer.toString(i) + " of 5");

                String rawData = "{\"endpointFeatures\":\"Agent,Presence2015,MessageProperties,CustomUserProperties,Highlights,Casts,CortanaBot,ModernBots,AutoIdleForWebApi,InviteFree\"}";
                String url = "https://ch1-client-s.gateway.messenger.live.com/v1/users/ME/endpoints";
                URL obj = new URL(url);
                HttpURLConnection con = (HttpURLConnection) obj.openConnection();

                con.setRequestMethod("POST");
                con.setRequestProperty("Authentication", "skypetoken=" + skypeToken);
                con.setDoOutput(true);

                OutputStreamWriter w = new OutputStreamWriter(con.getOutputStream(), "UTF-8");
                w.write(rawData);
                w.close();

                int responseCode = con.getResponseCode();
                if (responseCode != 201) {
                    String responseMsg = con.getResponseMessage();
                    logger.warning("Error getting endpoints: " + responseMsg);
                    continue;
                }

                String regToken = con.getHeaderField("Set-RegistrationToken");

                Connection conn = Jsoup.connect("https://ch1-client-s.gateway.messenger.live.com/v1/users/ME/presenceDocs/messagingService")
                        .maxBodySize(100 * 1024 * 1024)
                        .method(Method.GET)
                        .followRedirects(false)
                        .timeout(10000)
                        .ignoreContentType(true)
                        .ignoreHttpErrors(true);

                conn.header("RegistrationToken", regToken);

                Response resp = conn.execute();
                JSONObject endpointData = new JSONObject(resp.body());

                return endpointData;
            } catch (IOException e) {
                logger.warning("Could not get endpoint information!");
                logger.log(Level.SEVERE, "", e);
            }
        }

        return null;
    }

    Presence getUserCurrentStatus() {
        JSONObject endpointData = findEndpoint(skypeToken);

        if (endpointData == null)
            return null;

        logger.info("User availability: " + endpointData.optString("availability", "Not found") + ", status: " + endpointData.optString("status", "Not found"));
        switch (endpointData.optString("status")) {
            case "Online":
                return Presence.ONLINE;
            case "Busy":
                return Presence.BUSY;
            case "Idle":
                return Presence.IDLE;
            case "Away":
                return Presence.AWAY;
            case "Offline":
                return Presence.HIDDEN;
            default:
                return Presence.HIDDEN;
        }
    }

    private JSONArray getFinancialStats(String skypeToken, String username) throws IOException {
        if (skypeToken == null || username == null)
            return null;

        String url = "https://consumer.entitlement.skype.com/users/" + username + "/services?language=en";
        Connection conn = Jsoup.connect(url).maxBodySize(100 * 1024 * 1024).timeout(10000).method(Method.GET).ignoreContentType(true).ignoreHttpErrors(true);
        logger.finest("Sending GET request at " + url);

        conn.header("X-Skypetoken", skypeToken);
        conn.header("Accept", "application/json; ver=3.0");
        Response resp = conn.execute();

        if (resp.statusCode() != 200)
            return null;

        String body = resp.body();
        JSONArray financialStats = new JSONArray(body);

        return financialStats;
    }

}
