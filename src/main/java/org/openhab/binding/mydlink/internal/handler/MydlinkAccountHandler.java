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

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mydlink.internal.api.MydlinkApiClient;
import org.openhab.binding.mydlink.internal.api.MydlinkApiClient.MydlinkDevice;
import org.openhab.binding.mydlink.internal.api.MydlinkApiClient.MydlinkDeviceInfo;
import org.openhab.binding.mydlink.internal.config.MydlinkAccountConfig;
import org.openhab.binding.mydlink.internal.discovery.MydlinkDiscoveryService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MydlinkAccountHandler} is responsible for handling the mydlink account (Bridge).
 * It manages the API connection, authentication, and provides device information to Thing handlers.
 *
 * @author Sebastian Michel - Initial contribution
 */
@NonNullByDefault
public class MydlinkAccountHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(MydlinkAccountHandler.class);

    private @Nullable MydlinkApiClient apiClient;
    private @Nullable ScheduledFuture<?> pollingJob;
    private @Nullable MydlinkAccountConfig config;

    public MydlinkAccountHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing mydlink Account handler");

        config = getConfigAs(MydlinkAccountConfig.class);

        if (!config.isValid()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Email and password are required");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Connecting...");

        // Schedule connection in background
        scheduler.execute(this::connect);
    }

    /**
     * Connects to the mydlink API.
     */
    private void connect() {
        MydlinkAccountConfig cfg = config;
        if (cfg == null || cfg.email == null || cfg.password == null) {
            return;
        }

        try {
            MydlinkApiClient client = new MydlinkApiClient();

            if (client.login(cfg.email, cfg.password)) {
                this.apiClient = client;
                updateStatus(ThingStatus.ONLINE);

                // Start polling for device status updates
                startPolling(cfg.pollingInterval);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "Login failed - check credentials");
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to connect to mydlink: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    /**
     * Starts the polling job for status updates.
     */
    private void startPolling(int intervalSeconds) {
        stopPolling();

        pollingJob = scheduler.scheduleWithFixedDelay(this::pollDevices,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        logger.debug("Started polling with interval {} seconds", intervalSeconds);
    }

    /**
     * Stops the polling job.
     */
    private void stopPolling() {
        ScheduledFuture<?> job = pollingJob;
        if (job != null) {
            job.cancel(true);
            pollingJob = null;
        }
    }

    /**
     * Polls all devices for status updates.
     */
    private void pollDevices() {
        MydlinkApiClient client = apiClient;
        if (client == null || !client.isTokenValid()) {
            logger.debug("Reconnecting due to invalid token");
            connect();
            return;
        }

        // Notify child handlers to refresh their state
        getThing().getThings().forEach(thing -> {
            if (thing.getHandler() instanceof MydlinkPlugHandler plugHandler) {
                plugHandler.refreshState();
            }
        });
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Bridge doesn't have any channels that accept commands
    }

    @Override
    public void dispose() {
        logger.debug("Disposing mydlink Account handler");

        stopPolling();

        MydlinkApiClient client = apiClient;
        if (client != null) {
            client.close();
            apiClient = null;
        }

        super.dispose();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(MydlinkDiscoveryService.class);
    }

    /**
     * Gets the API client.
     *
     * @return the API client or null if not connected
     */
    public @Nullable MydlinkApiClient getApiClient() {
        return apiClient;
    }

    /**
     * Gets the user email address.
     *
     * @return the user email or null
     */
    public @Nullable String getUserEmail() {
        MydlinkApiClient client = apiClient;
        return client != null ? client.getUserEmail() : null;
    }

    /**
     * Gets the list of all devices.
     *
     * @return list of devices or empty list
     */
    public List<MydlinkDevice> getDevices() {
        MydlinkApiClient client = apiClient;
        if (client == null) {
            return Collections.emptyList();
        }

        try {
            return client.getDevices();
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to get devices: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Gets detailed device information.
     *
     * @param deviceId mydlink device ID
     * @param mac      MAC address
     * @return device info or null
     */
    public @Nullable MydlinkDeviceInfo getDeviceInfo(String deviceId, String mac) {
        MydlinkApiClient client = apiClient;
        if (client == null) {
            return null;
        }

        try {
            return client.getDeviceInfo(deviceId, mac);
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to get device info: {}", e.getMessage());
            return null;
        }
    }
}
