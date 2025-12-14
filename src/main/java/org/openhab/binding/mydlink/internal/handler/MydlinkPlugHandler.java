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
package org.openhab.binding.mydlink.internal.handler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mydlink.internal.MydlinkBindingConstants;
import org.openhab.binding.mydlink.internal.api.MydlinkApiClient.MydlinkDeviceInfo;
import org.openhab.binding.mydlink.internal.api.SignalAgentClient;
import org.openhab.binding.mydlink.internal.config.MydlinkPlugConfig;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MydlinkPlugHandler} handles a single mydlink Smart Plug.
 * It uses the Signal Agent protocol for device control.
 *
 * @author Sebastian Michel - Initial contribution
 */
@NonNullByDefault
public class MydlinkPlugHandler extends BaseThingHandler implements SignalAgentClient.StateChangeListener {

    private final Logger logger = LoggerFactory.getLogger(MydlinkPlugHandler.class);

    private @Nullable MydlinkPlugConfig config;
    private @Nullable MydlinkDeviceInfo deviceInfo;
    private @Nullable SignalAgentClient saClient;
    private @Nullable ScheduledFuture<?> reconnectJob;

    private boolean lastKnownSwitchState = false;

    public MydlinkPlugHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing mydlink Plug handler");

        config = getConfigAs(MydlinkPlugConfig.class);

        if (!config.isValid()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Device ID is required");
            return;
        }

        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "No bridge configured");
            return;
        }

        if (bridge.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Connecting...");

        // Schedule connection in background
        scheduler.execute(this::connect);
    }

    /**
     * Connects to the device via Signal Agent protocol.
     */
    private void connect() {
        MydlinkPlugConfig cfg = config;
        if (cfg == null || cfg.deviceId == null) {
            return;
        }

        MydlinkAccountHandler accountHandler = getAccountHandler();
        if (accountHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }

        // Get device info from API
        String mac = cfg.macAddress;
        if (mac == null || mac.isBlank()) {
            // Try to find MAC from device list
            for (var device : accountHandler.getDevices()) {
                if (cfg.deviceId.equals(device.mydlinkId)) {
                    mac = device.mac;
                    break;
                }
            }
        }

        if (mac == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Could not find MAC address for device");
            return;
        }

        MydlinkDeviceInfo info = accountHandler.getDeviceInfo(cfg.deviceId, mac);
        if (info == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Could not get device info");
            scheduleReconnect();
            return;
        }

        this.deviceInfo = info;

        // Update thing properties
        updateProperty("deviceName", info.deviceName);
        updateProperty("deviceModel", info.deviceModel);
        updateProperty("firmwareVersion", info.firmwareVersion);
        updateProperty("pinCode", info.pinCode);
        updateProperty("macAddress", info.mac);

        if (!info.online) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Device is offline");
            scheduleReconnect();
            return;
        }

        // Connect via Signal Agent
        String dcdUrl = info.dcdUrl;
        String deviceToken = info.deviceToken;
        String userEmail = accountHandler.getUserEmail();
        var apiClient = accountHandler.getApiClient();

        if (dcdUrl == null || deviceToken == null || userEmail == null || apiClient == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Missing DCD URL, device token, or user email");
            return;
        }

        String accessToken = apiClient.getAccessToken();
        if (accessToken == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "No access token available");
            return;
        }

        // Create and connect SA client
        SignalAgentClient client = new SignalAgentClient(accessToken, userEmail);
        client.setStateChangeListener(this);

        client.connect(dcdUrl).thenAccept(success -> {
            if (success) {
                this.saClient = client;
                updateStatus(ThingStatus.ONLINE);

                // Update initial state from API
                if (info.switchState != null) {
                    lastKnownSwitchState = info.switchState;
                    updateState(MydlinkBindingConstants.CHANNEL_SWITCH,
                            info.switchState ? OnOffType.ON : OnOffType.OFF);
                }

                updateState(MydlinkBindingConstants.CHANNEL_ONLINE, OnOffType.ON);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Failed to connect via Signal Agent");
                scheduleReconnect();
            }
        });
    }

    /**
     * Schedules a reconnection attempt.
     */
    private void scheduleReconnect() {
        cancelReconnect();

        reconnectJob = scheduler.schedule(this::connect, 60, TimeUnit.SECONDS);
        logger.debug("Scheduled reconnect in 60 seconds");
    }

    /**
     * Cancels any pending reconnection.
     */
    private void cancelReconnect() {
        ScheduledFuture<?> job = reconnectJob;
        if (job != null) {
            job.cancel(false);
            reconnectJob = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Handle command {} for channel {}", command, channelUID);

        if (command instanceof RefreshType) {
            refreshState();
            return;
        }

        if (MydlinkBindingConstants.CHANNEL_SWITCH.equals(channelUID.getId())) {
            if (command instanceof OnOffType) {
                boolean on = command == OnOffType.ON;
                switchPlug(on);
            }
        }
    }

    /**
     * Switches the plug on or off.
     */
    private void switchPlug(boolean on) {
        SignalAgentClient client = saClient;
        MydlinkDeviceInfo info = deviceInfo;

        if (client == null || !client.isConnected() || info == null || info.deviceToken == null) {
            logger.warn("Cannot switch plug - not connected");

            // Try to reconnect
            connect();
            return;
        }

        client.switchPlug(info.deviceToken, on).thenAccept(success -> {
            if (success) {
                logger.debug("Successfully switched plug to {}", on ? "ON" : "OFF");
                lastKnownSwitchState = on;
                updateState(MydlinkBindingConstants.CHANNEL_SWITCH, on ? OnOffType.ON : OnOffType.OFF);
            } else {
                logger.error("Failed to switch plug");
            }
        });
    }

    /**
     * Refreshes the device state from the API.
     */
    public void refreshState() {
        MydlinkPlugConfig cfg = config;
        MydlinkAccountHandler accountHandler = getAccountHandler();

        if (cfg == null || cfg.deviceId == null || accountHandler == null) {
            return;
        }

        String mac = cfg.macAddress;
        if (mac == null) {
            MydlinkDeviceInfo info = deviceInfo;
            mac = info != null ? info.mac : null;
        }

        if (mac == null) {
            return;
        }

        MydlinkDeviceInfo info = accountHandler.getDeviceInfo(cfg.deviceId, mac);
        if (info != null) {
            this.deviceInfo = info;

            // Update online state
            updateState(MydlinkBindingConstants.CHANNEL_ONLINE,
                    info.online ? OnOffType.ON : OnOffType.OFF);

            // Update switch state if available
            if (info.switchState != null) {
                lastKnownSwitchState = info.switchState;
                updateState(MydlinkBindingConstants.CHANNEL_SWITCH,
                        info.switchState ? OnOffType.ON : OnOffType.OFF);
            }

            // Handle online/offline transitions
            if (info.online && getThing().getStatus() != ThingStatus.ONLINE) {
                connect();
            } else if (!info.online && getThing().getStatus() == ThingStatus.ONLINE) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Device went offline");
            }
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            scheduler.execute(this::connect);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            disconnectSaClient();
        }
    }

    /**
     * Gets the account handler from the bridge.
     */
    private @Nullable MydlinkAccountHandler getAccountHandler() {
        Bridge bridge = getBridge();
        if (bridge != null && bridge.getHandler() instanceof MydlinkAccountHandler accountHandler) {
            return accountHandler;
        }
        return null;
    }

    /**
     * Disconnects the SA client.
     */
    private void disconnectSaClient() {
        SignalAgentClient client = saClient;
        if (client != null) {
            client.disconnect();
            saClient = null;
        }
    }

    @Override
    public void dispose() {
        logger.debug("Disposing mydlink Plug handler");

        cancelReconnect();
        disconnectSaClient();

        super.dispose();
    }

    // SignalAgentClient.StateChangeListener implementation

    @Override
    public void onSwitchStateChanged(String deviceId, boolean state) {
        MydlinkDeviceInfo info = deviceInfo;
        if (info != null && deviceId.equals(info.deviceToken)) {
            lastKnownSwitchState = state;
            updateState(MydlinkBindingConstants.CHANNEL_SWITCH, state ? OnOffType.ON : OnOffType.OFF);
        }
    }

    @Override
    public void onPowerChanged(String deviceId, double power) {
        MydlinkDeviceInfo info = deviceInfo;
        if (info != null && deviceId.equals(info.deviceToken)) {
            updateState(MydlinkBindingConstants.CHANNEL_POWER,
                    new QuantityType<>(power, Units.WATT));
        }
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        if (!connected) {
            logger.warn("Lost connection to device");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Lost WebSocket connection");
            scheduleReconnect();
        }
    }
}
