package org.raindrippy.serversideutils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HttpUtil {
	public static String postJson(String urlStr, String json) throws IOException {
	    URL url = URI.create(urlStr).toURL();
	    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
	    conn.setRequestMethod("POST");
	    conn.setRequestProperty("Content-Type", "application/json");
	    conn.setRequestProperty("User-Agent", "Java-Client/1.0");
	    conn.setRequestProperty("Connection", "close");
	    conn.setRequestProperty("Accept", "application/json");
	    
	    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
	    conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));
	    conn.setDoOutput(true);

	    try (OutputStream os = conn.getOutputStream()) {
	        os.write(jsonBytes);
	        os.flush(); // Ensure data is sent
	    }

	    int status = conn.getResponseCode();
	    InputStream stream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();

	    try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
	        StringBuilder response = new StringBuilder();
	        String line;
	        while ((line = br.readLine()) != null) {
	            response.append(line);
	        }
	        return response.toString();
	    } finally {
	        conn.disconnect();
	    }
	}

    public static class HttpResponse {
        public final int statusCode;
        public final String body;

        public HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
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
