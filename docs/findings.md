# Findings

Measured on a Blackview XPLORE 2 (`XPLO2EEA0001541`), Android 15 (sdk 35),
MediaTek MT6897 / MT6637, kernel 6.1.99, unrooted, 2026-11-27. The probe source
is `src/org/phonelink/p2pprobe`, and the unedited run-3 log is
`tests/fixtures/run3.log`.

## Method

The probe was built with `nix develop --command ./build.sh`, installed with
`adb install -r`, and started with the real P2P MAC that the framework hides:

```sh
mac=$(adb shell ip -o link | awk '/: p2p0:/{for(i=1;i<=NF;i++) if($i=="link/ether") print $(i+1)}')
adb shell am start -n org.phonelink.p2pprobe/.MainActivity \
    --es address "$mac" --ei hold 8000
adb logcat -d -s P2PSelfTest
```

The phone was associated with the laptop AP (`PhoneLink`, 5745 MHz) for the
coexistence test. The laptop's `iw dev wlp110s0 station dump` was sampled during
the group, and `phone-doctor` was run after the group was removed.

## Run 3 results

| Step | Result |
|---|---|
| Framework local P2P address | `02:00:00:00:00:00` (masked) |
| Real `p2p0` MAC supplied by the host | `d2:05:65:2e:5e:38` |
| `discoverPeers` (8 s) | 0 peers, self not listed |
| `connect(self)` with the real MAC | `failed ERROR` |
| `cancelConnect` | `failed BUSY` |
| `createGroup()` | `success`, owner, 0 clients |
| Group | SSID `DIRECT-KL-XPLORE 2`, interface `p2p-p2p0-2` |
| `connect(own group)` | `failed ERROR` |
| STA link before / during / after | `client true / true / true` |
| Laptop station association | 1961 s -> 1990 s, no reassociation |
| `phone-doctor` after | `ready=true`, 0 fail; proxy 200 on `192.168.77.232:1081` |

The same verdict was produced with the masked address (run 1) and with the real
MAC (runs 2 and 3): the phone cannot discover or connect to itself, and it
cannot join the group it just created. Self-P2P is not an Android permission
problem that a different intent extra can solve.

## Runs 4 and 5: the hotspot changes the answer

- **Run 4 (hotspot off).** `createGroup()` succeeded as GO, SSID
  `DIRECT-KL-XPLORE 2`, interface `p2p-p2p0-0`, `192.168.49.1/24`, tether state
  `LocalHotspotState`, 0 clients. The group ran on **6135 MHz (6 GHz)**. The
  laptop's Intel 8265 is 2.4/5 GHz only, so it could not see or join the group;
  the `DIRECT-` SSID never appeared in the laptop scan.
- **Run 5 (hotspot on).** Every P2P call returned `BUSY`; logcat says
  `WifiP2pService: Failed to setup interface for P2P` and
  `Interface resource is not available`. `ap0` holds the only AP-capable
  interface, and the HAL does not offer AP + P2P.

A P2P group and the hotspot cannot be up together. The 6 GHz default is a
separate laptop-compatibility problem: forcing a 5 GHz group would need
`WifiP2pConfig.Builder().setGroupOperatingFrequency(5180)` in the probe. That
test is still open (issue #1).

## Why self-P2P is impossible

Three independent layers agree; see `docs/direct-links.md` for the transport
comparison.

1. **Framework.** `WifiP2pServiceImpl.connect()` only takes the "join an
   existing group by SSID + passphrase" fast path when no group exists. Once the
   phone is GO, `GroupCreatedState` treats CONNECT as an invitation to a
   discovered peer, so the phone cannot ask to join its own group.
2. **wpa_supplicant.** `p2p_connect()` looks the peer up with
   `p2p_get_device(peer_addr)`; the peer table is fed only by received frames,
   and the one self entry that can appear is explicitly skipped
   (`continue; /* ignore our own entry */`).
3. **Hardware.** `phy0` is the only wiphy. The GO ran on the STA's channel
   (5745 MHz, same-channel concurrency), and a radio cannot receive its own
   transmissions. Even the non-P2P variant (STA joining the phone's own SoftAP)
   has the same limit.

The framework also masks the local P2P address (`02:00:00:00:00:00`) and hides
`p2p0`'s hardware address from apps; the host can still read it from
`ip -o link` (sysfs is permission-denied to the shell on this build).

## What does coexist

`createGroup()` and a **client** STA link coexist: in run 3 the phone was a
client of the laptop AP on 5745 MHz and the group ran on the same channel. That
is the STA + P2P combination. It is not AP + P2P: the hotspot (`ap0`) blocks the
group (run 5). The phone-as-GO topology is still worth testing as a link for the
laptop once the channel is forced, but it does not turn the phone into a Wi-Fi
client.

## The short bootstrap for 5555

A temporary STA is enough to get legacy adb onto the phone. In the measured
sequence the phone joined the laptop AP, Keeper set `adb_wifi_enabled=1` and
pinned `5555` over its TLS key, and the AP was then turned off. The toggle
reverted to `0` and the TLS listener died, but `service.adb.tcp.port=5555` and
the listener survived. The phone hotspot then carried
`adb connect <ap0>:5555` with no STA at all.

Keeper's log proves the phone did the pin:

```text
WifiDebug: info pin.tls_ok asked adbd over TLS to listen on 5555
WifiDebug: pin check: closed (asked the app's TLS client)
WifiDebug: pin.state listening on 5555
```

The full sequence is in [`short-bootstrap.md`](short-bootstrap.md). A cold boot
still loses `5555`, the toggle and the hotspot. Nothing recreates the port until
a trusted Wi-Fi client network appears; then Keeper re-arms the toggle and
re-pins `5555` in about 17 s with no host adb command
(`tests/fixtures/cold-boot-5555-timeline.log`).

## What this means for automatic wireless debugging

- The Android Wireless debugging **toggle** needs a real `TRANSPORT_WIFI`
  client network. A hotspot or P2P GO does not provide one; the platform logs
  `Not connected to any wireless network. Not enabling adbwifi.` and reverts
  `adb_wifi_enabled` (measured, see `docs/hotspot.md`).
- Legacy adb `5555` is different: `adbd` listens on the wildcard address, so it
  works over the phone's hotspot once `service.adb.tcp.port=5555` is set. The
  open problem is making that listener exist after a cold boot without root,
  USB, or a temporary STA.
- Termux can run an adb client and keep an existing listener alive, but it
  cannot create the listener or write the property; see `docs/termux.md`.

## Reproduce

The probe needs the real MAC because the framework masks its own. It removes
the group at the end and never disconnects the STA link. Keep USB adb attached
while experimenting; a group can briefly disturb `wlan0`.

```sh
nix develop --command ./build.sh
adb install -r build/p2p-self-probe.apk
adb shell pm grant org.phonelink.p2pprobe android.permission.NEARBY_WIFI_DEVICES
adb logcat -c
adb shell am start -n org.phonelink.p2pprobe/.MainActivity --es address <p2p0-mac>
adb logcat -d -s P2PSelfTest
```
