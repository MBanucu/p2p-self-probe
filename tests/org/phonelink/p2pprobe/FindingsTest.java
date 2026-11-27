package org.phonelink.p2pprobe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locks the documented experiment facts to the recorded fixtures.
 *
 * <p>These are curated transcripts, not raw logcat: the point is that a docs
 * change cannot silently contradict the evidence the repository points at.
 * Plain JDK, no JUnit, same style as {@link ProbeLogicTest}.
 */
public final class FindingsTest {
    private static int checks;

    private FindingsTest() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "tests/fixtures");

        contains(dir, "short-bootstrap.log", "info pin.state listening on 5555");
        contains(dir, "short-bootstrap.log", "adb_wifi_enabled -> 0");
        contains(dir, "short-bootstrap.log", "adb connect 10.231.103.217:5555");
        contains(dir, "short-bootstrap.log", "PERSISTED_ACROSS_HOTSPOT_TOGGLE");

        contains(dir, "cold-boot.log", "boot_completed after ~19s");
        contains(dir, "cold-boot.log", "service.adb.tcp.port\n(empty)");
        contains(dir, "cold-boot.log", "no 5555 listener");
        contains(dir, "cold-boot.log", "info pin.state listening on 5555");

        contains(dir, "usb-self-adb.log", "Not connected to any wireless network. Not enabling adbwifi.");
        contains(dir, "usb-self-adb.log", "failed to connect to '127.0.0.1:5556'");
        contains(dir, "usb-self-adb.log", "ls: cannot access '/dev/socket/adbd': Permission denied");
        contains(dir, "usb-self-adb.log", "No shell command implementation");

        contains(dir, "p2p-hotspot-busy.log", "Failed to setup interface for P2P");
        contains(dir, "p2p-hotspot-busy.log", "createGroup: failed BUSY");

        contains(dir, "p2p-go-6ghz.log", "channelFrequency=6135");
        contains(dir, "p2p-go-6ghz.log", "192.168.49.1/24");

        contains(dir, "hotspot-subnet.log", "10.231.103.217/24");
        contains(dir, "hotspot-subnet.log", "changed=no");

        contains(dir, "cold-boot-5555-timeline.log", "STA association -> 5555 listening        ~17 s");
        contains(dir, "cold-boot-5555-timeline.log", "boot -> 5555 without any STA             never");
        contains(dir, "cold-boot-5555-timeline.log", "adbd_wifi_secure_connect: connected host-41");
        contains(dir, "cold-boot-5555-timeline.log", "TlsServer running on port 40733");
        contains(dir, "cold-boot-5555-timeline.log", "info pin.state listening on 5555");
        contains(dir, "cold-boot-5555-timeline.log", "ensure.changed setting turned off: Enabled wireless debugging");
        contains(dir, "cold-boot-5555-timeline.log", "Network disconnected. Disabling adbwifi.");
        contains(dir, "cold-boot-5555-timeline.log", "remembered SSID: PhoneLink");

        contains(dir, "p2p-toggle-gate.log", "channelFrequency=6055, groupRole=GroupOwner");
        contains(dir, "p2p-toggle-gate.log", "inet 192.168.49.1/24");
        contains(dir, "p2p-toggle-gate.log", "Not connected to any wireless network. Not enabling adbwifi.");
        contains(dir, "p2p-toggle-gate.log", "WifiDebug: enabled wireless debugging");

        contains(dir, "p2p-go-5ghz.log", "channelFrequency=5180, groupRole=GroupOwner");
        contains(dir, "p2p-go-5ghz.log", "group ssid: DIRECT-KL-5G");
        contains(dir, "p2p-go-5ghz.log", "inet 192.168.49.149/24");
        contains(dir, "p2p-go-5ghz.log", "connected to 192.168.49.1:5555");
        contains(dir, "p2p-go-5ghz.log", "2000\n5555\n0");
        contains(dir, "p2p-go-5ghz.log", "STILL_OK_30S");

        contains(dir, "usb-tcpip-bootstrap.log", "restarting in TCP mode port: 5555");
        contains(dir, "usb-tcpip-bootstrap.log", "5555_listeners=0");
        contains(dir, "usb-tcpip-bootstrap.log", "5555_listeners=1");
        contains(dir, "usb-tcpip-bootstrap.log", "property_visible_s=1.016");
        contains(dir, "usb-tcpip-bootstrap.log", "Wifi is disabled");
        contains(dir, "usb-tcpip-bootstrap.log", "2000\n5555\n0");

        contains(dir, "hotspot-self-connect.log", "bssid = 2a:aa:b4:59:d3:a0");
        contains(dir, "hotspot-self-connect.log", "targetBssid=2a:aa:b4:59:d3:a0");
        contains(dir, "hotspot-self-connect.log", "NETWORK_NOT_FOUND_EVENT");
        contains(dir, "hotspot-self-connect.log", "Wifi is not connected");
        contains(dir, "hotspot-self-connect.log", "cmd wifi forget-network 5");

        System.out.println("ok: " + checks + " findings");
    }

    private static void contains(Path dir, String file, String needle) throws IOException {
        String text = Files.readString(dir.resolve(file), StandardCharsets.UTF_8);
        if (!text.contains(needle)) {
            throw new AssertionError(file + " does not contain: " + needle);
        }
        checks++;
    }
}
