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

`createGroup()` and the laptop STA link coexist on this phone: the laptop kept
its association across the group and the proxy stayed reachable. That makes the
phone-as-GO topology worth testing as a link for the laptop, but it does not
turn the phone into a Wi-Fi client.

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
