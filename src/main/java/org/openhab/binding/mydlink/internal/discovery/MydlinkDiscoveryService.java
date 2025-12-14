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
package org.openhab.binding.mydlink.internal.discovery;

import static org.openhab.binding.mydlink.internal.MydlinkBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mydlink.internal.api.MydlinkApiClient.MydlinkDevice;
import org.openhab.binding.mydlink.internal.handler.MydlinkAccountHandler;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovery service for mydlink devices.
 * Discovers all devices registered to the mydlink account.
 *
 * @author Sebastian Michel - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = MydlinkDiscoveryService.class)
@NonNullByDefault
public class MydlinkDiscoveryService extends AbstractThingHandlerDiscoveryService<MydlinkAccountHandler> {

    private final Logger logger = LoggerFactory.getLogger(MydlinkDiscoveryService.class);

    private static final int DISCOVERY_TIMEOUT_SECONDS = 10;

    public MydlinkDiscoveryService() {
        super(MydlinkAccountHandler.class, Set.of(THING_TYPE_SMART_PLUG), DISCOVERY_TIMEOUT_SECONDS, false);
    }

    @Override
    protected void startScan() {
        logger.debug("Starting mydlink device discovery");

        MydlinkAccountHandler handler = thingHandler;
        if (handler == null) {
            logger.warn("Account handler not available");
            return;
        }

        ThingUID bridgeUID = handler.getThing().getUID();

        for (MydlinkDevice device : handler.getDevices()) {
            if (device.mydlinkId == null || device.mac == null) {
                continue;
            }

            ThingTypeUID thingTypeUID = THING_TYPE_SMART_PLUG;
            ThingUID thingUID = new ThingUID(thingTypeUID, bridgeUID, device.mydlinkId);

            Map<String, Object> properties = new HashMap<>();
            properties.put("deviceId", device.mydlinkId);
            properties.put("macAddress", device.mac);

            String label = device.deviceName != null ? device.deviceName : "mydlink Plug " + device.mydlinkId;
            if (device.deviceModel != null) {
                label += " (" + device.deviceModel + ")";
            }

            DiscoveryResult result = DiscoveryResultBuilder.create(thingUID)
                    .withThingType(thingTypeUID)
                    .withBridge(bridgeUID)
                    .withProperties(properties)
                    .withRepresentationProperty("deviceId")
                    .withLabel(label)
                    .build();

            thingDiscovered(result);

            logger.debug("Discovered device: {} ({})", device.deviceName, device.mydlinkId);
        }
    }

    @Override
    public void initialize() {
        thingHandler = getThingHandler();
        super.initialize();
    }
}
