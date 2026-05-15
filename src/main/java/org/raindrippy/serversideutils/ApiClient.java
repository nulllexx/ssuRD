package org.raindrippy.serversideutils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ApiClient {

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

    public boolean queryLogin(String username, String password) {
        try {
            Gson gson = new Gson();
            LoginRequest request = new LoginRequest(username, password);
            String json = gson.toJson(request);
            String response = HttpUtil.postJson("https://bakosmp.go.ro/api/v-creds", json);
            System.out.println("RAW RESPONSE: " + response);
            LoginResponse loginResponse = gson.fromJson(response, LoginResponse.class);
            return loginResponse.isIsMember();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
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
            e.printStackTrace();
            return false;
        }
    }
}
