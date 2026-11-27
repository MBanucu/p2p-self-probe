# Direct links that can carry adb

Scope: the XPLORE 2 (Android 15, unrooted) and the NixOS laptop (Intel 8265,
`wlp110s0`, NetworkManager 1.56, wpa_supplicant 2.12). Read-only research plus
the measurements linked below; no root and no USB Wi-Fi dongle.

## The key reframing

`adbd` binds the wildcard address. With `service.adb.tcp.port=5555`, the phone
has one dual-stack `[::]:5555` listener, and any routable interface is enough
for `adb connect` — P2P group, SoftAP, rndis, Ethernet. The phone does not need
a Wi-Fi **client** interface for the transport, and `mdnsd` advertises the TLS
port per interface.

The Android Wireless debugging **toggle** is different: `AdbDebuggingManager`
reads `WifiManager.getConnectionInfo()` — the **client-mode** `WifiInfo` — and
needs `getNetworkId() != -1` plus a BSSID. Only an association to an external AP
provides that. Measured on 2026-11-27: with only a P2P GO up (`p2p-p2p0-0`,
`192.168.49.1/24`, 6055 MHz) the write was refused
(`Not connected to any wireless network. Not enabling adbwifi.`), and Keeper's
own write was reverted 2 ms later. The same holds for the hotspot, rndis, PAN,
NAN and VPN. So: every row below can carry `5555`, none of them can switch the
toggle on. Separate the two questions when comparing transports.

## Ranked options

| # | Transport | Verdict | Phone role | No root? | No external AP? | Survives laptop reboot? |
|---|---|---|---|---|---|---|
| 1 | P2P, phone = GO, laptop = legacy station | **works on 5 GHz; adb 5180 MHz proven** | GO | yes | yes | yes, with a keep-alive service |
| 2 | Phone hotspot, laptop = client | **works; adb 5555 proven** | AP | yes | yes | yes, but `5555` needs a pin per phone boot |
| 3 | Keeper + real AP (laptop/router) | **works; toggle + 5555 automatic** | STA | yes | no | yes |
| 4 | USB tether (RNDIS) + `5555` | works, cable-bound | n/a | yes | n/a | no (cable) |
| 5 | P2P, laptop = GO, phone = client | possible, more moving parts | GC | yes | no (laptop AP) | yes, but GC has no NetworkAgent |
| 6 | Bluetooth PAN | works, ~1–2 Mbps, UI toggle | PANU/NAP | yes | no | weak; PANU does not auto-reconnect |
| 7 | USB-C Ethernet | works, wired | host | yes | n/a | no (cable) |
| 8 | Wi-Fi Aware / NAN | impractical | n/a | n/a | n/a | no Linux stack |
| 9 | USB Wi-Fi second radio | needs root/module | n/a | **no** | yes | **out of scope** |
| 10 | USB self-loopback / virtual port | needs root/custom kernel | n/a | **no** | n/a | **out of scope** |

## Notes

**1. P2P GO + laptop station — works on 5 GHz.** A P2P group is a normal WPA2 AP
with an SSID `DIRECT-…`; the laptop joins it as an ordinary station, so no P2P
stack is needed on Linux. Measured 2026-11-27: the default GO came up on 6 GHz
(6135/6055 MHz, invisible to the 8265), but a config with
`setGroupOperatingFrequency(5180)` plus an SSID and passphrase put the GO on
**5180 MHz**; the laptop joined `DIRECT-KL-5G` on channel 36, the phone's DHCP
gave `192.168.49.149`, and `adb connect 192.168.49.1:5555` worked with the
toggle off, no AP, no hotspot and no cable. The frequency is a HAL hint, not a
guarantee; the builder refuses a bare frequency (`peer address must be set if
network name and passphrase are not set`). Still open: the group lives on the
probe's timer, so it needs a foreground service to stay up, and the hotspot
cannot be up at the same time (`Failed to setup interface for P2P`). Fixture:
`tests/fixtures/p2p-go-5ghz.log`.

**2. Hotspot + laptop.** The only phone-hosted link proven end to end for adb.
The subnet is randomized per boot (`10.31.180.0/24`, then `10.231.103.0/24`), so
read `ap0` and connect to that; `adb <ap0>:5555` works with
`adb_wifi_enabled=0`. No root. The phone cannot join its own hotspot (one
radio); the laptop is the client. `5555` does not survive a phone reboot, but a
short temporary STA can pin it; see [`short-bootstrap.md`](short-bootstrap.md).

**3. Keeper + real AP.** Wireless ADB Keeper writes `adb_wifi_enabled=1` with
`WRITE_SECURE_SETTINGS`, pins `5555` via its trusted TLS key, and auto-joins a
saved network. Its `isWifiConnected()` gate is enforced by
`AdbDebuggingManager`; hotspot/P2P do not satisfy it. The AP is only needed
until the pin: after `tcpip:5555` the STA can go away and the legacy listener
remains.

**4. USB tether.** `rndis,adb` already gives an IP link; `adb connect
<phone-rndis-ip>:5555` worked, including IPv6 link-local, and mDNS resolved
`Android.local`. It is the recovery path, not a wireless replacement.

**5. Laptop as P2P GO.** In principle the phone is a P2P client, but Android's
P2P client produces only a legacy `NetworkInfo(TYPE_WIFI_P2P)`, not a
`NetworkAgent` (`isP2pGcNetworkAgentEnabled=false`), so ConnectivityManager and
the Keeper gate do not see it. More moving parts than #1 for no gain.

**6. Bluetooth PAN.** `PanService` / `bt-pan` exists, but PANU is a Settings
toggle, throughput is ~1–2 Mbps, and the laptop needs BlueZ NAP + bridge/DHCP
for the other direction. Fine for `adb shell`, poor for sshfs.

**7. USB-C Ethernet.** `EthernetTracker` matches `(usb|eth)\d+` and the kernel
has `r8152`/`ax88179`/`asix`/`cdc_*`, so an adapter works, but it is a cable
and host mode removes `rndis0`/USB adb. A control experiment, not a solution.

**8. NAN.** The feature list lacks `android.hardware.wifi.aware`; Linux has no
usable NAN stack. Dead end.

**9. USB Wi-Fi second radio.** The only topology that could give a genuine
client interface without an external AP is a dongle AP on `wlan1` with the
internal `wlan0` joining it. Android's Wi-Fi HAL never manages a USB netdev;
it needs root, a loadable driver matching the vendor kernel, and manual
`hostapd`/`wpa_supplicant`. Out of scope by the no-root decision.

**10. USB loopback / virtual port.** One UDC, `dummy_hcd`/`raw-gadget`/`usbip`
not enabled, configfs root-only, DRP one role at a time. Even a successful
in-phone loopback would not give the laptop a route. Out of scope.

## Strongest next test

**P2P GO + laptop station** (#1), because it is wireless, needs no root, no
external AP and no P2P stack on Linux, and the group lives on the phone so a
laptop reboot should not destroy it. If it fails, **hotspot + 5555** (#2) is the
proven fallback, with a one-time pin per phone boot.

See `docs/findings.md`, `docs/hotspot.md`, `docs/keeper.md`, and
`ROADMAP.md`.
