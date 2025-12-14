# openHAB mydlink Binding

This binding integrates D-Link mydlink Smart Plugs into openHAB.

## Supported Things

| Thing Type | Description |
|------------|-------------|
| `account`  | Bridge - mydlink cloud account |
| `smartplug` | D-Link Smart Plug |

### Supported Devices

- DSP-W115
- DSP-W118
- DSP-W218 (tested)
- DSP-W245
- DSP-W320

## Discovery

Once you have configured a mydlink account bridge, the binding will automatically discover all smart plugs registered to your account.

## Thing Configuration

### Bridge Configuration (mydlink Account)

| Parameter | Required | Description |
|-----------|----------|-------------|
| `email` | Yes | Your mydlink account email address |
| `password` | Yes | Your mydlink account password |
| `pollingInterval` | No | Status polling interval in seconds (default: 60) |

### Thing Configuration (Smart Plug)

| Parameter | Required | Description |
|-----------|----------|-------------|
| `deviceId` | Yes | The mydlink device ID |
| `macAddress` | No | Device MAC address (auto-discovered) |

## Channels

| Channel | Type | Description |
|---------|------|-------------|
| `switch` | Switch | Turn the plug ON/OFF |
| `power` | Number:Power | Current power consumption (read-only) |
| `online` | Switch | Device online status (read-only) |

## Full Example

### things/mydlink.things

```java
Bridge mydlink:account:home "mydlink Account" [ email="your-email@example.com", password="your-password" ] {
    Thing smartplug living_room "Living Room Plug" [ deviceId="51581499" ]
    Thing smartplug bedroom "Bedroom Plug" [ deviceId="51581567" ]
}
```

### items/mydlink.items

```java
Switch LivingRoom_Plug "Living Room Plug" { channel="mydlink:smartplug:home:living_room:switch" }
Number:Power LivingRoom_Power "Power [%.1f W]" { channel="mydlink:smartplug:home:living_room:power" }
Switch LivingRoom_Online "Online" { channel="mydlink:smartplug:home:living_room:online" }

Switch Bedroom_Plug "Bedroom Plug" { channel="mydlink:smartplug:home:bedroom:switch" }
```

### sitemaps/mydlink.sitemap

```perl
sitemap mydlink label="mydlink Smart Plugs" {
    Frame label="Living Room" {
        Switch item=LivingRoom_Plug
        Text item=LivingRoom_Power
        Text item=LivingRoom_Online
    }
    Frame label="Bedroom" {
        Switch item=Bedroom_Plug
    }
}
```

## Installation

### Using Pre-built JAR (Recommended)

1. Download the JAR from the `release/` folder
2. Copy it to your openHAB addons folder:
   ```bash
   cp org.openhab.binding.mydlink-5.0.0-SNAPSHOT.jar /usr/share/openhab/addons/
   ```
3. Restart openHAB or install via Karaf console:
   ```
   bundle:install file:/usr/share/openhab/addons/org.openhab.binding.mydlink-5.0.0-SNAPSHOT.jar
   bundle:start org.openhab.binding.mydlink
   ```

### Compatibility

- openHAB 4.x and 5.x
- Java 17 or later

## Technical Details

This binding uses two protocols to communicate with mydlink devices:

1. **REST API** - For authentication, device discovery, and status polling
2. **Signal Agent (SA) Protocol** - WebSocket-based protocol for real-time device control

Gen2 devices (like DSP-W218) require the Signal Agent protocol for actual device switching. The REST API alone cannot control these devices.

## Building

This binding can be built as a standalone addon or integrated into the openHAB addons repository.

### Standalone Build

```bash
mvn clean package
```

The resulting JAR file can be found in `target/` and can be deployed to openHAB's `addons/` folder.

### Integration with openHAB Addons

To integrate with the official openHAB addons repository:

1. Clone the openHAB addons repository
2. Copy this binding to `bundles/org.openhab.binding.mydlink`
3. Add the binding to the parent pom.xml
4. Build with Maven

## Credits

- Protocol reverse engineering based on analysis of the mydlink Android app
- OAuth2 implementation inspired by [MyDlink-API-Python](https://github.com/ageof/MyDlink-API-Python)

## License

This binding is licensed under the Eclipse Public License 2.0.
