package org.raindrippy.serversideutils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ApiClient {

    // Fallback logger for offline/unit-test construction. In production Main injects the
    // plugin logger (also a java.util.logging.Logger) so these lines reliably reach the Paper
    // console/log files; a standalone JUL logger is not guaranteed to be bridged into Log4j2.
    private static final Logger DEFAULT_LOGGER = Logger.getLogger(ApiClient.class.getName());

    /** Max chars of a response body echoed into a log line (avoids dumping huge error pages). */
    private static final int MAX_LOGGED_BODY = 500;

    private final Logger logger;

    // Shared login endpoint for users and admins; returns a "userToken" Set-Cookie
    // used to authenticate the ban request. (Not /api/v-creds, which is MC-auth only.)
    private static final String ADMIN_LOGIN_URL = "https://bakosmp.go.ro/api/login";
    private static final String BAN_URL = "https://bakosmp.go.ro/api/admin/moderate";
    // 3-day ban type (matches the "3d" type used in queryStatus).
    private static final String BAN_TYPE = "3d";

    private final String adminUser;
    private final String adminPwd;

    public ApiClient() {
        this(null, null, null);
    }

    public ApiClient(String adminUser, String adminPwd) {
        this(adminUser, adminPwd, null);
    }

    public ApiClient(String adminUser, String adminPwd, Logger logger) {
        this.adminUser = adminUser;
        this.adminPwd = adminPwd;
        this.logger = (logger != null) ? logger : DEFAULT_LOGGER;
    }

    private static class LoginRequest {
        @SuppressWarnings("unused")
        private String username;
        @SuppressWarnings("unused")
        private String password;

        LoginRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    private static class LoginResponse {
        private boolean isMember;

        boolean isIsMember() {
            return isMember;
        }
    }

    public JSONObject getUserCredentials(String username, JSONArray credentialsArray) {
        for (Object o : credentialsArray) {
            JSONObject userObj = (JSONObject) o;
            if (username.equals(userObj.get("username"))) {
                return userObj;
            }
        }
        return null;
    }

    /** Outcome of a credential check against /api/v-creds. */
    public enum LoginResult {
        SUCCESS,
        // password is bad
        INVALID_CREDENTIALS,
        // not whitelisted acc
        NOT_MEMBER,
        // mother of all uh-ohs
        ERROR
    }

    /**
     * Checks credentials against /api/v-creds, distinguishing a wrong password (401)
     * from a valid-but-non-member account (200 with {@code isMember: false}).
     */
    public LoginResult queryCredentials(String username, String password) {
        try {
            Gson gson = new Gson();
            LoginRequest request = new LoginRequest(username, password);
            String json = gson.toJson(request);
            HttpUtil.HttpResponse res = HttpUtil.postJson("https://bakosmp.go.ro/api/v-creds", json, null);
            if (res.statusCode == 401) {
                return LoginResult.INVALID_CREDENTIALS;
            }
            if (res.statusCode < 200 || res.statusCode >= 300) {
                // Never log the request/credentials; only the response status + a trimmed body.
                logger.warning("Credential check against /api/v-creds failed: HTTP "
                        + res.statusCode + ", body=" + trimBody(res.body));
                return LoginResult.ERROR;
            }
            LoginResponse loginResponse = gson.fromJson(res.body, LoginResponse.class);
            if (loginResponse != null && loginResponse.isIsMember()) {
                return LoginResult.SUCCESS;
            }
            return LoginResult.NOT_MEMBER;
        } catch (Exception e) {
            // Never log username/password; the exception carries neither.
            logger.log(Level.WARNING, "Credential check against /api/v-creds failed", e);
            return LoginResult.ERROR;
        }
    }

    public boolean queryLogin(String username, String password) {
        return queryCredentials(username, password) == LoginResult.SUCCESS;
    }

    /** Collapses a response body to a single, length-capped line safe to drop into a log. */
    private static String trimBody(String body) {
        if (body == null) return "<none>";
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_LOGGED_BODY ? oneLine.substring(0, MAX_LOGGED_BODY) + "..." : oneLine;
    }

    public boolean queryStatus(String username, Player p) {
        try {
            String url = "https://bakosmp.go.ro/api/accstatus-cuser?username="
                    + URLEncoder.encode(username, StandardCharsets.UTF_8);
            HttpUtil.HttpResponse res = HttpUtil.get(url);
            if (res.statusCode == 200) return true;

            String l1 = "Account Moderated";
            String reviewed = "Reviewed: null (PDT)";
            String modnote = "Moderator note: null";
            String notice = "Your account has been moderated.";
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = mapper.readValue(res.body, Map.class);
            JSONObject obj = new JSONObject(map);
            Object banObj = obj.get("banInfo");
            if (banObj instanceof Map<?, ?>) {
                Map<?, ?> banInfo = (Map<?, ?>) banObj;
                String type = (String) banInfo.get("type");
                reviewed = banInfo.get("moderatedTimePDT") + " (PDT)";
                modnote = (String) banInfo.get("modNote");
                switch (type) {
                    case "1d":
                        l1 = "Banned for 1 Day";
                        notice = "Your account has been disabled for 1 day.";
                        break;
                    case "3d":
                        l1 = "Banned for 3 Days";
                        notice = "Your account has been disabled for 3 days.";
                        break;
                    case "7d":
                        l1 = "Banned for 7 Days";
                        notice = "Your account has been disabled for 7 days.";
                        break;
                    case "14d":
                        l1 = "Banned for 14 Days";
                        notice = "Your account has been disabled for 14 days.";
                        break;
                    case "perm":
                        l1 = "Account Terminated";
                        notice = "Your account has been terminated.";
                        break;
                    case "poison":
                        l1 = "Account Terminated";
                        notice = "Your account has been terminated, and new account creation has been disabled from this device.";
                        break;
                    default:
                        l1 = "Account Moderated";
                        notice = "Your account has been moderated.";
                        break;
                }
            }
            String kickMsg = "&l&6" + l1
                    + "\n&rWe've determined that your prior actions have been against our rules & have taken action against your account.\n"
                    + "&4Reviewed&r: " + reviewed
                    + "\n&4Moderator note&r: " + modnote
                    + "\n\n&l&c" + notice
                    + "\n\n&rIf you wish to appeal, please contact us via Discord or by using one of the methods listed on our website.";
            kickMsg = ChatColor.translateAlternateColorCodes('&', kickMsg);
            p.kickPlayer(kickMsg);
            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to query account status for " + username, e);
            return false;
        }
    }

    /** Cached admin session cookie (userToken); refreshed on demand or when it goes stale. */
    private volatile String cachedCookie;

    /**
     * Bans the RainDrippy account for {@link #BAN_TYPE} using the admin session cookie.
     * Reuses a cached cookie when available; if the ban is rejected as unauthenticated
     * (expired/invalid token), it forces a fresh admin login and retries once so a
     * borked token is never kept around.
     *
     * @param username the account username to ban (pulled from the player's saved credentials)
     * @return true if the ban endpoint responded 2xx
     */
    public boolean banAccount(String username) {
        try {
            String banBody = new Gson().toJson(buildBanBody(username));

            String cookie = getAdminCookie(false);
            if (cookie == null) {
                logger.warning("No admin cookie available; cannot ban " + username);
                return false;
            }

            HttpUtil.HttpResponse banRes = HttpUtil.postJson(BAN_URL, banBody, cookie);

            // Token likely expired/invalid -> drop it, log in fresh, and retry once.
            if (isAuthFailure(banRes.statusCode)) {
                logger.warning("Ban request rejected for " + username + " (status "
                        + banRes.statusCode + "); refreshing admin token and retrying.");
                cookie = getAdminCookie(true);
                if (cookie == null) {
                    logger.warning("Re-login failed; cannot ban " + username);
                    return false;
                }
                banRes = HttpUtil.postJson(BAN_URL, banBody, cookie);
            }

            return banRes.statusCode >= 200 && banRes.statusCode < 300;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ban request failed for " + username, e);
            return false;
        }
    }

    // Package-private for unit testing (pure logic, no behavior change).
    Map<String, Object> buildBanBody(String username) {
        Map<String, Object> banBody = new HashMap<>();
        banBody.put("username", username);
        banBody.put("type", BAN_TYPE);
        banBody.put("modNote", "Automatic ban due to breaking of in-game rules. For more information, please contact the server staff.");
        banBody.put("incriminatory", null);
        banBody.put("poisonHWID", null);
        banBody.put("makeAdmin", false);
        return banBody;
    }

    /** 401/403 => credentials/token no longer accepted, so the cookie should be discarded. */
    // Package-private for unit testing.
    static boolean isAuthFailure(int statusCode) {
        return statusCode == 401 || statusCode == 403;
    }

    /**
     * Returns the admin session cookie, logging in as admin when there is no cached cookie
     * or when {@code forceRefresh} is set. Clears the cache before re-logging in so a stale
     * cookie is never retained. Returns null if login fails to yield a cookie.
     */
    private synchronized String getAdminCookie(boolean forceRefresh) {
        if (!forceRefresh && cachedCookie != null) {
            return cachedCookie;
        }
        cachedCookie = null;
        try {
            // Log in as admin to obtain the session cookie (userToken).
            Map<String, Object> loginBody = new HashMap<>();
            loginBody.put("username", adminUser);
            loginBody.put("password", adminPwd);
            HttpUtil.HttpResponse loginRes = HttpUtil.postJson(ADMIN_LOGIN_URL, new Gson().toJson(loginBody), null);
            String cookie = HttpUtil.cookiesToHeader(loginRes.setCookies);
            if (cookie == null) {
                logger.warning("Admin login returned no cookie (status " + loginRes.statusCode + ").");
                return null;
            }
            cachedCookie = cookie;
            return cachedCookie;
        } catch (Exception e) {
            // Log the failure but not the login body (admin credentials).
            logger.log(Level.WARNING, "Admin login failed", e);
            return null;
        }
    }
}
