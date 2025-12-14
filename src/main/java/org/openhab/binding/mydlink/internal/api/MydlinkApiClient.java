/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.mydlink.internal.api;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mydlink.internal.MydlinkBindingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for the mydlink REST API.
 * Handles OAuth2 authentication, device listing, and device information retrieval.
 *
 * @author Sebastian Michel - Initial contribution
 */
@NonNullByDefault
public class MydlinkApiClient {

    private final Logger logger = LoggerFactory.getLogger(MydlinkApiClient.class);

    private final HttpClient httpClient;
    private final Gson gson;

    private @Nullable String accessToken;
    private @Nullable String apiSite;
    private @Nullable String userEmail;
    private Instant tokenExpires = Instant.MIN;

    public MydlinkApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(MydlinkBindingConstants.API_TIMEOUT))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.gson = new Gson();
    }

    /**
     * Calculates MD5 hash of a string.
     */
    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return String.format("%032x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /**
     * URL-encodes a string.
     */
    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Authenticates with the mydlink API using email and password.
     *
     * @param email    mydlink account email
     * @param password mydlink account password (plain text)
     * @return true if authentication was successful
     * @throws IOException          if network error occurs
     * @throws InterruptedException if interrupted
     */
    public boolean login(String email, String password) throws IOException, InterruptedException {
        logger.debug("Logging in to mydlink with email: {}", email);

        this.userEmail = email;
        String passwordHash = md5Hash(password);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        // Build OAuth URL path
        String oauthPath = "/oauth/authorize2";
        String params = String.join("&",
                "client_id=" + MydlinkBindingConstants.CLIENT_ID,
                "redirect_uri=" + urlEncode("https://mydlink.com"),
                "user_name=" + urlEncode(email),
                "password=" + passwordHash,
                "response_type=token",
                "timestamp=" + timestamp,
                "uc_id=openHAB",
                "uc_name=openHAB-mydlink-binding");

        String loginUrl = oauthPath + "?" + params;
        String signature = md5Hash(loginUrl + MydlinkBindingConstants.OAUTH_SECRET);
        String fullUrl = MydlinkBindingConstants.API_URL + loginUrl + "&sig=" + signature;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Accept", "application/json")
                .header("User-Agent", "openHAB/4.0 mydlink-binding")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Handle redirect response
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            String location = response.headers().firstValue("Location").orElse("");
            logger.debug("Redirect to: {}", location);

            if (location.contains("access_token=")) {
                return parseTokenFromRedirect(location);
            }
        }

        // Handle JSON response
        if (response.statusCode() == 200) {
            return parseTokenFromJson(response.body());
        }

        logger.error("Login failed with status: {} - {}", response.statusCode(), response.body());
        return false;
    }

    /**
     * Parses access token from redirect URL.
     */
    private boolean parseTokenFromRedirect(String location) {
        try {
            // Token can be in fragment (#) or query (?)
            String params = location.contains("#") ? location.split("#")[1] : location.split("\\?")[1];

            for (String param : params.split("&")) {
                String[] kv = param.split("=");
                if (kv.length == 2) {
                    switch (kv[0]) {
                        case "access_token":
                            this.accessToken = kv[1];
                            break;
                        case "api_site":
                            this.apiSite = kv[1];
                            break;
                        case "expires_in":
                            long expiresIn = Long.parseLong(kv[1]);
                            this.tokenExpires = Instant.now().plusSeconds(expiresIn);
                            break;
                    }
                }
            }

            if (accessToken != null) {
                logger.info("Successfully logged in to mydlink. API site: {}", apiSite);
                return true;
            }
        } catch (Exception e) {
            logger.error("Failed to parse redirect URL: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Parses access token from JSON response.
     */
    private boolean parseTokenFromJson(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("access_token")) {
                this.accessToken = obj.get("access_token").getAsString();
                this.apiSite = obj.has("api_site") ? obj.get("api_site").getAsString() : null;
                long expiresIn = obj.has("expires_in") ? obj.get("expires_in").getAsLong() : 172800;
                this.tokenExpires = Instant.now().plusSeconds(expiresIn);

                logger.info("Successfully logged in to mydlink. API site: {}", apiSite);
                return true;
            }
        } catch (Exception e) {
            logger.error("Failed to parse JSON response: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Returns the base URL for API requests.
     */
    private String getBaseUrl() {
        String base = apiSite != null ? apiSite : MydlinkBindingConstants.API_URL;
        if (!base.startsWith("http")) {
            base = "https://" + base;
        }
        return base;
    }

    /**
     * Checks if the token is valid.
     */
    public boolean isTokenValid() {
        return accessToken != null && Instant.now().isBefore(tokenExpires);
    }

    /**
     * Gets the current access token.
     */
    public @Nullable String getAccessToken() {
        return accessToken;
    }

    /**
     * Gets the user email address.
     */
    public @Nullable String getUserEmail() {
        return userEmail;
    }

    /**
     * Gets the list of all registered devices.
     *
     * @return list of device information
     * @throws IOException          if network error occurs
     * @throws InterruptedException if interrupted
     */
    public List<MydlinkDevice> getDevices() throws IOException, InterruptedException {
        List<MydlinkDevice> devices = new ArrayList<>();

        if (!isTokenValid()) {
            logger.warn("Token not valid, cannot get devices");
            return devices;
        }

        String url = getBaseUrl() + "/me/device/list?access_token=" + accessToken;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "openHAB/4.0 mydlink-binding")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray data = obj.getAsJsonArray("data");

            if (data != null) {
                for (JsonElement elem : data) {
                    JsonObject dev = elem.getAsJsonObject();
                    MydlinkDevice device = new MydlinkDevice();
                    device.mydlinkId = getStringOrNull(dev, "mydlink_id");
                    device.mac = getStringOrNull(dev, "mac");
                    device.deviceName = getStringOrNull(dev, "device_name");
                    device.deviceModel = getStringOrNull(dev, "device_model");
                    device.online = dev.has("online") && dev.get("online").getAsBoolean();
                    devices.add(device);
                }
            }
        } else {
            logger.error("Failed to get devices: {} - {}", response.statusCode(), response.body());
        }

        return devices;
    }

    /**
     * Gets detailed information for a specific device.
     *
     * @param deviceId mydlink device ID
     * @param mac      MAC address of the device
     * @return device information or null if not found
     * @throws IOException          if network error occurs
     * @throws InterruptedException if interrupted
     */
    public @Nullable MydlinkDeviceInfo getDeviceInfo(String deviceId, String mac)
            throws IOException, InterruptedException {
        if (!isTokenValid()) {
            logger.warn("Token not valid, cannot get device info");
            return null;
        }

        String url = getBaseUrl() + "/me/device/info?access_token=" + accessToken;

        JsonObject payload = new JsonObject();
        JsonArray dataArray = new JsonArray();
        JsonObject deviceObj = new JsonObject();
        deviceObj.addProperty("mac", mac);
        deviceObj.addProperty("mydlink_id", deviceId);
        dataArray.add(deviceObj);
        payload.add("data", dataArray);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "openHAB/4.0 mydlink-binding")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray data = obj.getAsJsonArray("data");

            if (data != null && data.size() > 0) {
                JsonObject dev = data.get(0).getAsJsonObject();
                MydlinkDeviceInfo info = new MydlinkDeviceInfo();
                info.mydlinkId = getStringOrNull(dev, "mydlink_id");
                info.mac = mac;
                info.deviceName = getStringOrNull(dev, "device_name");
                info.deviceModel = getStringOrNull(dev, "device_model");
                info.online = dev.has("online") && dev.get("online").getAsBoolean();
                info.dcdUrl = getStringOrNull(dev, "DCD");
                info.deviceToken = getStringOrNull(dev, "device_token");
                info.pinCode = getStringOrNull(dev, "pin_code");
                info.privateIp = getStringOrNull(dev, "private_ip");
                info.privatePort = getStringOrNull(dev, "private_port");
                info.firmwareVersion = getStringOrNull(dev, "fw_ver");

                // Extract current switch state from change_cache
                if (dev.has("change_cache")) {
                    JsonObject cache = dev.getAsJsonObject("change_cache");
                    if (cache.has("setting_change")) {
                        JsonArray settings = cache.getAsJsonArray("setting_change");
                        for (JsonElement setting : settings) {
                            JsonObject s = setting.getAsJsonObject();
                            if (s.has("metadata")) {
                                JsonObject metadata = s.getAsJsonObject("metadata");
                                int type = metadata.has("type") ? metadata.get("type").getAsInt() : 0;
                                if (type == MydlinkBindingConstants.SA_TYPE_PLUG) {
                                    info.switchState = metadata.has("value") && metadata.get("value").getAsInt() == 1;
                                }
                            }
                        }
                    }
                }

                return info;
            }
        } else {
            logger.error("Failed to get device info: {} - {}", response.statusCode(), response.body());
        }

        return null;
    }

    /**
     * Gets user account information.
     *
     * @return user info or null if not available
     * @throws IOException          if network error occurs
     * @throws InterruptedException if interrupted
     */
    public @Nullable MydlinkUserInfo getUserInfo() throws IOException, InterruptedException {
        if (!isTokenValid()) {
            logger.warn("Token not valid, cannot get user info");
            return null;
        }

        String url = getBaseUrl() + "/me/user/info?access_token=" + accessToken;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "openHAB/4.0 mydlink-binding")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject data = obj.getAsJsonObject("data");

            if (data != null) {
                MydlinkUserInfo info = new MydlinkUserInfo();
                info.email = getStringOrNull(data, "email");
                info.userUuid = getStringOrNull(data, "user_uuid");
                info.country = getStringOrNull(data, "country");
                info.language = getStringOrNull(data, "language");
                return info;
            }
        }

        return null;
    }

    /**
     * Helper to safely get string from JSON object.
     */
    private @Nullable String getStringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /**
     * Closes the client and releases resources.
     */
    public void close() {
        this.accessToken = null;
        this.apiSite = null;
        this.tokenExpires = Instant.MIN;
    }

    /**
     * Basic device information from device list.
     */
    public static class MydlinkDevice {
        public @Nullable String mydlinkId;
        public @Nullable String mac;
        public @Nullable String deviceName;
        public @Nullable String deviceModel;
        public boolean online;
    }

    /**
     * Detailed device information.
     */
    public static class MydlinkDeviceInfo {
        public @Nullable String mydlinkId;
        public @Nullable String mac;
        public @Nullable String deviceName;
        public @Nullable String deviceModel;
        public boolean online;
        public @Nullable String dcdUrl;
        public @Nullable String deviceToken;
        public @Nullable String pinCode;
        public @Nullable String privateIp;
        public @Nullable String privatePort;
        public @Nullable String firmwareVersion;
        public @Nullable Boolean switchState;
    }

    /**
     * User account information.
     */
    public static class MydlinkUserInfo {
        public @Nullable String email;
        public @Nullable String userUuid;
        public @Nullable String country;
        public @Nullable String language;
    }
}
