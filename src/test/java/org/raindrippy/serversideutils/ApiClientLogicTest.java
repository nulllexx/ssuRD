package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** Offline pure-logic tests for {@link ApiClient} (no network hit). */
class ApiClientLogicTest {

    private final ApiClient apiClient = new ApiClient();

    @SuppressWarnings("unchecked")
    private static JSONObject user(String username) {
        JSONObject o = new JSONObject();
        o.put("username", username);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static JSONArray arrayOf(JSONObject... objs) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : objs) {
            arr.add(o);
        }
        return arr;
    }

    @Test
    @DisplayName("getUserCredentials returns the object whose username matches")
    void findsMatch() {
        JSONObject bob = user("Bob");
        JSONArray arr = arrayOf(user("Alice"), bob, user("Carol"));
        assertEquals(bob, apiClient.getUserCredentials("Bob", arr));
    }

    @Test
    @DisplayName("getUserCredentials returns null when no username matches")
    void noMatch() {
        JSONArray arr = arrayOf(user("Alice"), user("Carol"));
        assertNull(apiClient.getUserCredentials("Bob", arr));
    }

    @Test
    @DisplayName("getUserCredentials is case-sensitive")
    void caseSensitive() {
        JSONArray arr = arrayOf(user("Bob"));
        assertNull(apiClient.getUserCredentials("bob", arr));
    }

    @Test
    @DisplayName("getUserCredentials on an empty array -> null")
    void emptyArray() {
        assertNull(apiClient.getUserCredentials("Bob", new JSONArray()));
    }

    @Test
    @DisplayName("buildBanBody has the expected shape for a 3-day ban")
    void banBodyShape() {
        Map<String, Object> body = apiClient.buildBanBody("Bob");
        assertEquals("Bob", body.get("username"));
        assertEquals("3d", body.get("type"));
        assertTrue(((String) body.get("modNote")).length() > 0);
        assertTrue(body.containsKey("incriminatory"));
        assertNull(body.get("incriminatory"));
        assertTrue(body.containsKey("poisonHWID"));
        assertNull(body.get("poisonHWID"));
        assertEquals(Boolean.FALSE, body.get("makeAdmin"));
    }

    @Test
    @DisplayName("queryCredentials logs the HTTP status on a non-2xx response (and never the password)")
    void nonSuccessStatusIsLogged() {
        // Capture what the injected logger emits.
        Logger logger = Logger.getLogger("ApiClientLoggingTest");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);

        ApiClient client = new ApiClient(null, null, logger);
        try (MockedStatic<HttpUtil> http = mockStatic(HttpUtil.class)) {
            http.when(() -> HttpUtil.postJson(anyString(), anyString(), any()))
                    .thenReturn(new HttpUtil.HttpResponse(502, "<html>502 Bad Gateway</html>"));

            ApiClient.LoginResult result = client.queryCredentials("bob", "secret-pw");
            assertEquals(ApiClient.LoginResult.ERROR, result, "non-2xx must map to ERROR");
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(1, records.size(), "the silent non-2xx path must now emit exactly one log record");
        LogRecord rec = records.get(0);
        assertEquals(Level.WARNING, rec.getLevel());
        assertTrue(rec.getMessage().contains("502"), "log should carry the HTTP status for diagnosis");
        assertFalse(rec.getMessage().contains("secret-pw"), "log must never contain the password");
    }

    @Test
    @DisplayName("isAuthFailure is true only for 401 and 403")
    void authFailureBoundaries() {
        assertTrue(ApiClient.isAuthFailure(401));
        assertTrue(ApiClient.isAuthFailure(403));
        assertFalse(ApiClient.isAuthFailure(200));
        assertFalse(ApiClient.isAuthFailure(400));
        assertFalse(ApiClient.isAuthFailure(404));
        assertFalse(ApiClient.isAuthFailure(500));
    }
}
