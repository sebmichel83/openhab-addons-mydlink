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
package org.openhab.binding.mydlink.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link MydlinkBindingConstants} class defines common constants used across
 * the mydlink binding.
 *
 * @author Sebastian Michel - Initial contribution
 */
@NonNullByDefault
public class MydlinkBindingConstants {

    public static final String BINDING_ID = "mydlink";

    // Bridge Type (Account)
    public static final ThingTypeUID THING_TYPE_ACCOUNT = new ThingTypeUID(BINDING_ID, "account");

    // Thing Types (Devices)
    public static final ThingTypeUID THING_TYPE_SMART_PLUG = new ThingTypeUID(BINDING_ID, "smartplug");

    // Channel IDs
    public static final String CHANNEL_SWITCH = "switch";
    public static final String CHANNEL_POWER = "power";
    public static final String CHANNEL_ONLINE = "online";

    // API Configuration
    public static final String API_URL = "https://api.auto.mydlink.com";
    public static final String CLIENT_ID = "521ea1890143662c7597864ffb6fc816";
    public static final String OAUTH_SECRET = "82aac78b6d02239942afd8fe9b3c6d22";

    // Signal Agent Protocol Constants
    public static final String SA_SUBPROTOCOL = "mydlink-ws";
    public static final int SA_TYPE_PLUG = 16;
    public static final int SA_TYPE_POWER = 9;
    public static final int SA_EVENT_SETTING_CHANGE = 61;

    // Timeouts (in seconds)
    public static final int API_TIMEOUT = 30;
    public static final int WEBSOCKET_TIMEOUT = 10;
    public static final int POLLING_INTERVAL = 60;
}
