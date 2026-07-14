package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
