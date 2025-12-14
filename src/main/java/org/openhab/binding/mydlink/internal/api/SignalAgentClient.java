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

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.mydlink.internal.MydlinkBindingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for the mydlink Signal Agent (SA) protocol.
 * This WebSocket-based protocol is required for actual device control on Gen2 devices.
 *
 * @author Sebastian Michel - Initial contribution
 */
@NonNullByDefault
public class SignalAgentClient implements WebSocketListener {

    private final Logger logger = LoggerFactory.getLogger(SignalAgentClient.class);

    private final String accessToken;
    private final String userEmail;
    private final Gson gson;
    private final String clientId;

    private @Nullable WebSocketClient wsClient;
    private @Nullable Session wsSession;
    private final AtomicInteger sequenceId = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    private boolean signedIn = false;

    // Device info received after sign-in (needed for set_setting)
    private @Nullable String connectedDeviceId;
    private @Nullable String connectedDeviceToken;

    /**
     * Listener interface for device state changes.
     */
    public interface StateChangeListener {
        void onSwitchStateChanged(String deviceId, boolean state);

        void onPowerChanged(String deviceId, double power);

        void onConnectionStateChanged(boolean connected);
    }

    private @Nullable StateChangeListener stateChangeListener;

    /**
     * Creates a new Signal Agent client.
     *
     * @param accessToken OAuth2 access token
     * @param userEmail   user's email address (used as owner_id)
     */
    public SignalAgentClient(String accessToken, String userEmail) {
        this.accessToken = accessToken;
        this.userEmail = userEmail;
        this.gson = new Gson();
        this.clientId = java.util.UUID.randomUUID().toString();
    }

    /**
     * Sets the state change listener.
     */
    public void setStateChangeListener(@Nullable StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    /**
     * Connects to the DCD relay server.
     *
     * @param dcdUrl the DCD WebSocket URL (e.g., wss://mp-eu-dcdda.auto.mydlink.com:443/SwitchCamera)
     * @return true if connection was successful
     */
    public CompletableFuture<Boolean> connect(String dcdUrl) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                wsClient = new WebSocketClient();
                wsClient.start();

                ClientUpgradeRequest request = new ClientUpgradeRequest();
                request.setSubProtocols(MydlinkBindingConstants.SA_SUBPROTOCOL);
                request.setHeader("Origin", "https://mydlink.com");

                URI uri = URI.create(dcdUrl);
                // connect() returns Future<Session>, we need to wait for it
                Session session = wsClient.connect(this, uri, request).get(
                        MydlinkBindingConstants.WEBSOCKET_TIMEOUT, TimeUnit.SECONDS);

                if (session != null && session.isOpen()) {
                    logger.debug("Connected to DCD relay: {}", dcdUrl);
                    // Sign in after connection
                    signIn().whenComplete((signInResult, signInError) -> {
                        if (signInError != null || !Boolean.TRUE.equals(signInResult)) {
                            logger.error("Sign-in failed");
                            result.complete(false);
                        } else {
                            result.complete(true);
                        }
                    });
                } else {
                    logger.error("Failed to connect to DCD: session is null or closed");
                    result.complete(false);
                }
            } catch (Exception e) {
                logger.error("Failed to connect to DCD: {}", e.getMessage());
                result.complete(false);
            }
        });

        return result;
    }

    /**
     * Signs in to the DCD relay.
     */
    private CompletableFuture<Boolean> signIn() {
        CompletableFuture<Boolean> result = new CompletableFuture<>();

        int seqId = sequenceId.incrementAndGet();

        JsonObject signInMsg = new JsonObject();
        signInMsg.addProperty("command", "sign_in");
        signInMsg.addProperty("sequence_id", seqId);
        signInMsg.addProperty("timestamp", Instant.now().getEpochSecond());
        signInMsg.addProperty("client_name", "openHAB-mydlink-binding");
        signInMsg.addProperty("role", "client_agent");
        signInMsg.addProperty("owner_id", userEmail);
        signInMsg.addProperty("owner_token", accessToken);

        // Scope array
        com.google.gson.JsonArray scopeArray = new com.google.gson.JsonArray();
        for (String scope : Arrays.asList("user", "device:status", "device:control", "viewing", "photo", "policy",
                "client", "event")) {
            scopeArray.add(scope);
        }
        signInMsg.add("scope", scopeArray);

        CompletableFuture<JsonObject> responseFuture = new CompletableFuture<>();
        pendingRequests.put(seqId, responseFuture);

        sendMessage(signInMsg);

        responseFuture.orTimeout(MydlinkBindingConstants.WEBSOCKET_TIMEOUT, TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    pendingRequests.remove(seqId);
                    if (error != null) {
                        logger.error("Sign-in timeout");
                        result.complete(false);
                    } else {
                        int code = response.has("code") ? response.get("code").getAsInt() : -1;
                        if (code == 0) {
                            logger.info("Successfully signed in to DCD relay");
                            signedIn = true;
                            result.complete(true);
                        } else {
                            String message = response.has("message") ? response.get("message").getAsString() : "unknown";
                            logger.error("Sign-in failed: code={}, message={}", code, message);
                            result.complete(false);
                        }
                    }
                });

        return result;
    }

    /**
     * Sets the device info for this connection.
     * Must be called after getting device info from API.
     *
     * @param macAddress  the device's MAC address (used as device_id in SA protocol)
     * @param deviceToken the device's full token (MAC-UUID format)
     */
    public void setDeviceInfo(String macAddress, String deviceToken) {
        // In SA protocol, device_id is the MAC address without colons
        this.connectedDeviceId = macAddress.replace(":", "").toUpperCase();
        this.connectedDeviceToken = deviceToken;
    }

    /**
     * Switches a plug on or off.
     *
     * @param deviceToken full device token
     * @param on          true to turn on, false to turn off
     * @return true if command was successful
     */
    public CompletableFuture<Boolean> switchPlug(String deviceToken, boolean on) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();

        if (!signedIn) {
            logger.warn("Not signed in, cannot switch plug");
            result.complete(false);
            return result;
        }

        String devId = connectedDeviceId;
        String devToken = connectedDeviceToken != null ? connectedDeviceToken : deviceToken;

        if (devId == null) {
            logger.warn("No device ID set, cannot switch plug");
            result.complete(false);
            return result;
        }

        int seqId = sequenceId.incrementAndGet();

        // Build setting object (matches Python implementation)
        JsonObject metadata = new JsonObject();
        metadata.addProperty("value", on ? 1 : 0);

        JsonObject setting = new JsonObject();
        setting.addProperty("uid", 0);
        setting.addProperty("idx", 0);
        setting.addProperty("type", MydlinkBindingConstants.SA_TYPE_PLUG);
        setting.add("metadata", metadata);

        com.google.gson.JsonArray settingArray = new com.google.gson.JsonArray();
        settingArray.add(setting);

        // Build command message (matches Python implementation)
        JsonObject setSettingMsg = new JsonObject();
        setSettingMsg.addProperty("command", "set_setting");
        setSettingMsg.addProperty("client_id", clientId);
        setSettingMsg.addProperty("device_id", devId);
        setSettingMsg.addProperty("device_token", devToken);
        setSettingMsg.addProperty("timestamp", Instant.now().getEpochSecond());
        setSettingMsg.addProperty("sequence_id", seqId);
        setSettingMsg.add("setting", settingArray);

        CompletableFuture<JsonObject> responseFuture = new CompletableFuture<>();
        pendingRequests.put(seqId, responseFuture);

        sendMessage(setSettingMsg);

        responseFuture.orTimeout(MydlinkBindingConstants.WEBSOCKET_TIMEOUT, TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    pendingRequests.remove(seqId);
                    if (error != null) {
                        logger.error("set_setting timeout");
                        result.complete(false);
                    } else {
                        int code = response.has("code") ? response.get("code").getAsInt() : -1;
                        if (code == 0) {
                            logger.debug("Switch command successful: {}", on ? "ON" : "OFF");
                            result.complete(true);
                        } else {
                            String message = response.has("message") ? response.get("message").getAsString() : "unknown";
                            logger.error("Switch failed: code={}, message={}", code, message);
                            result.complete(false);
                        }
                    }
                });

        return result;
    }

    /**
     * Sends a message over the WebSocket.
     */
    private void sendMessage(JsonObject message) {
        Session session = wsSession;
        if (session != null && session.isOpen()) {
            try {
                String json = gson.toJson(message);
                logger.debug("Sending: {}", json);
                session.getRemote().sendString(json);
            } catch (Exception e) {
                logger.error("Failed to send message: {}", e.getMessage());
            }
        } else {
            logger.warn("WebSocket not connected");
        }
    }

    /**
     * Disconnects from the DCD relay.
     */
    public void disconnect() {
        try {
            Session session = wsSession;
            if (session != null) {
                session.close();
            }
            WebSocketClient client = wsClient;
            if (client != null) {
                client.stop();
            }
        } catch (Exception e) {
            logger.debug("Error during disconnect: {}", e.getMessage());
        }
        wsSession = null;
        wsClient = null;
        signedIn = false;
    }

    /**
     * Checks if connected and signed in.
     */
    public boolean isConnected() {
        Session session = wsSession;
        return session != null && session.isOpen() && signedIn;
    }

    // WebSocketListener implementation

    @Override
    public void onWebSocketConnect(@Nullable Session session) {
        this.wsSession = session;
        logger.debug("WebSocket connected");
    }

    @Override
    public void onWebSocketClose(int statusCode, @Nullable String reason) {
        logger.debug("WebSocket closed: {} - {}", statusCode, reason);
        wsSession = null;
        signedIn = false;

        StateChangeListener listener = stateChangeListener;
        if (listener != null) {
            listener.onConnectionStateChanged(false);
        }
    }

    @Override
    public void onWebSocketError(@Nullable Throwable cause) {
        logger.error("WebSocket error: {}", cause != null ? cause.getMessage() : "unknown");
    }

    @Override
    public void onWebSocketBinary(byte @Nullable [] payload, int offset, int len) {
        // Not used
    }

    @Override
    public void onWebSocketText(@Nullable String message) {
        if (message == null) {
            return;
        }

        logger.debug("Received: {}", message);

        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String command = json.has("command") ? json.get("command").getAsString() : "";

            // Handle response to pending request
            if (json.has("sequence_id")) {
                int seqId = json.get("sequence_id").getAsInt();
                CompletableFuture<JsonObject> future = pendingRequests.get(seqId);
                if (future != null) {
                    future.complete(json);
                }
            }

            // Handle events
            if ("event".equals(command)) {
                handleEvent(json);
            }
        } catch (Exception e) {
            logger.error("Failed to parse message: {}", e.getMessage());
        }
    }

    /**
     * Handles incoming event messages.
     */
    private void handleEvent(JsonObject message) {
        // The event data is nested in the "event" field
        JsonObject event = message.has("event") ? message.getAsJsonObject("event") : message;

        int type = event.has("type") ? event.get("type").getAsInt() : 0;
        String deviceId = message.has("device_id") ? message.get("device_id").getAsString() : "";

        if (type == MydlinkBindingConstants.SA_EVENT_SETTING_CHANGE) {
            // Setting change event - this confirms the set_setting was successful
            logger.debug("Setting change event received for device {}", deviceId);

            // Resolve any pending set_setting requests (they don't get a direct response)
            JsonObject successResponse = new JsonObject();
            successResponse.addProperty("code", 0);
            successResponse.addProperty("message", "confirmed by event");

            for (var entry : pendingRequests.entrySet()) {
                CompletableFuture<JsonObject> future = entry.getValue();
                if (!future.isDone()) {
                    future.complete(successResponse);
                    pendingRequests.remove(entry.getKey());
                    logger.debug("Resolved pending request {} via event confirmation", entry.getKey());
                    break; // Only resolve one at a time
                }
            }

            // Also notify listener about state change
            if (event.has("metadata")) {
                JsonObject metadata = event.getAsJsonObject("metadata");
                int settingType = metadata.has("type") ? metadata.get("type").getAsInt() : 0;

                StateChangeListener listener = stateChangeListener;
                if (listener != null) {
                    if (settingType == MydlinkBindingConstants.SA_TYPE_PLUG) {
                        boolean state = metadata.has("value") && metadata.get("value").getAsInt() == 1;
                        logger.debug("Switch state changed for {}: {}", deviceId, state);
                        listener.onSwitchStateChanged(deviceId, state);
                    } else if (settingType == MydlinkBindingConstants.SA_TYPE_POWER) {
                        double power = metadata.has("value") ? metadata.get("value").getAsDouble() : 0;
                        logger.debug("Power changed for {}: {} W", deviceId, power);
                        listener.onPowerChanged(deviceId, power);
                    }
                }
            }
        }
    }
}
