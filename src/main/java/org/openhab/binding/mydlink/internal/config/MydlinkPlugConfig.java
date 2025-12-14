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
package org.openhab.binding.mydlink.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Configuration class for mydlink Smart Plug (Thing).
 *
 * @author Sebastian Michel - Initial contribution
 */
@NonNullByDefault
public class MydlinkPlugConfig {

    /**
     * The mydlink device ID
     */
    public @Nullable String deviceId;

    /**
     * The device MAC address (optional, can be auto-discovered)
     */
    public @Nullable String macAddress;

    /**
     * Validates the configuration.
     *
     * @return true if configuration is valid
     */
    public boolean isValid() {
        return deviceId != null && !deviceId.isBlank();
    }
}
