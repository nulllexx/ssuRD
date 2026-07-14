package org.raindrippy.serversideutils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HttpUtil {
	public static String postJson(String urlStr, String json) throws IOException {
	    return postJson(urlStr, json, null).body;
	}

	public static HttpResponse postJson(String urlStr, String json, String cookieHeader) throws IOException {
	    URL url = URI.create(urlStr).toURL();
	    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
	    conn.setRequestMethod("POST");
	    conn.setRequestProperty("Content-Type", "application/json");
	    conn.setRequestProperty("User-Agent", "Java-Client/1.0");
	    conn.setRequestProperty("Connection", "close");
	    conn.setRequestProperty("Accept", "application/json");
	    if (cookieHeader != null) {
	        conn.setRequestProperty("Cookie", cookieHeader);
	    }

	    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
	    conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));
	    conn.setDoOutput(true);

	    try (OutputStream os = conn.getOutputStream()) {
	        os.write(jsonBytes);
	        os.flush(); // Ensure data is sent
	    }

	    int status = conn.getResponseCode();
	    List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
	    InputStream stream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();

	    try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
	        StringBuilder response = new StringBuilder();
	        String line;
	        while ((line = br.readLine()) != null) {
	            response.append(line);
	        }
	        return new HttpResponse(status, response.toString(), setCookies);
	    } finally {
	        conn.disconnect();
	    }
	}

	/**
	 * Collapses a list of Set-Cookie header values into a single Cookie request header,
	 * keeping only each cookie's name=value pair. Returns null if there are no cookies.
	 */
	public static String cookiesToHeader(List<String> setCookies) {
	    if (setCookies == null || setCookies.isEmpty()) return null;
	    StringBuilder sb = new StringBuilder();
	    for (String setCookie : setCookies) {
	        if (setCookie == null || setCookie.isEmpty()) continue;
	        String pair = setCookie.split(";", 2)[0].trim();
	        if (pair.isEmpty()) continue;
	        if (sb.length() > 0) sb.append("; ");
	        sb.append(pair);
	    }
	    return sb.length() == 0 ? null : sb.toString();
	}

    public static class HttpResponse {
        public final int statusCode;
        public final String body;
        public final List<String> setCookies;

        public HttpResponse(int statusCode, String body) {
            this(statusCode, body, null);
        }

        public HttpResponse(int statusCode, String body, List<String> setCookies) {
            this.statusCode = statusCode;
            this.body = body;
            this.setCookies = setCookies;
        }
    }
    public static HttpResponse get(String urlStr) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        int status = conn.getResponseCode();

        // choose stream based on status (errors are in getErrorStream)
        InputStream is = (status >= 200 && status < 300) 
                ? conn.getInputStream() 
                : conn.getErrorStream();

        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line.trim());
            }
        }

        return new HttpResponse(status, response.toString());
    }
}
