# Hotspot: the phone as AP, and the "connect to itself" idea

Measured on the XPLORE 2 on 2026-11-27. The phone was hosting its own hotspot
(`XPLORE 2`), the NixOS laptop was joined as a client, and no external AP was
involved.

## What works

| Item | Run A (earlier boot) | Run B (after reboot) |
|---|---|---|
| Phone hotspot interface | `ap0` = `10.31.180.89/24` | `ap0` = `10.231.103.217/24` |
| Laptop client | `wlp110s0` = `10.31.180.149/24` | `wlp110s0` = `10.231.103.149/24` |
| adbd listener | `[::]:5555` (`service.adb.tcp.port=5555`) | `[::]:5555` |
| `adb connect <ap0>:5555` | works, uid 2000(shell) | works, uid 2000(shell) |
| Phone `adb_wifi_enabled` | `0` (toggle off) | `0` (toggle off) |

This is the practical no-external-AP result: the phone hosts the link, the
laptop joins it, and legacy adb works over `5555` even though the phone is not
a Wi-Fi client and the Wireless debugging toggle is off.

**The subnet is not stable across boots.** Android 14+ Tethering randomized it
from `10.31.180.0/24` to `10.231.103.0/24` on the next boot; a hotspot off/on in
the same boot kept the second subnet. Read `ap0` every time instead of
hardcoding the address:

```sh
ip=$(adb shell ip -4 -o addr show ap0 | awk '{print $4}' | cut -d/ -f1)
adb connect "$ip:5555"
```

The catch is that `5555` is volatile. It survives hotspot/P2P transitions and
losing the STA (see [`short-bootstrap.md`](short-bootstrap.md)), but not a
reboot; `persist.adb.tcp.port` is empty, and the shell cannot set either
property:

```text
$ adb shell setprop service.adb.tcp.port 5555
Failed to set property 'service.adb.tcp.port' to '5555'.

$ adb shell setprop persist.adb.tcp.port ''
Failed to set property 'persist.adb.tcp.port' to ''.
```

## The hotspot and a P2P group are mutually exclusive

With the hotspot up, the P2P probe gets `discoverPeers: failed BUSY`,
`connect(self): failed BUSY` and `createGroup: failed BUSY`, and logcat says:

```text
WifiP2pService: Failed to setup interface for P2P
WifiP2pService: Interface resource is not available
```

`ap0` holds the only AP-capable interface, and the HAL does not offer AP + P2P
(see the table below). The hotspot must be off before a P2P group can be
created. With it off, `createGroup()` succeeded, but the GO ran on **6135 MHz
(6 GHz)** — invisible to the laptop's 2.4/5 GHz Intel 8265. The fix, if P2P is
pursued, is to force a 5 GHz group channel in the probe; see
`docs/findings.md` and issue #1.

## Why the phone cannot join its own hotspot

`STA + AP Concurrency Supported: true` is real, but it means **STA to an external
AP + SoftAP**, not STA to the SoftAP on the same device. AP and STA must share a
channel in one band, i.e. one radio, and a radio cannot receive its own beacon,
auth or association frames. Two-STA on this chip is band-split
(`mIsMultiInternetSameBandConnectionAllowed=false`, no EHT/MLO); retuning the
STA to the AP's channel puts both virtual interfaces on the same radio.

Android itself has no self-SSID/self-BSSID check: `WifiServiceImpl.connect()` only checks privilege, `ClientModeImpl` and `WifiConnectivityManager` do not reference SoftAP, and a client connect does not stop the hotspot under STA+AP. The blocker is the radio. The STA's scan cache lists nearby APs but never the phone's own BSSID; the BSSID exists only in the SoftAP config. A CPython/`ip route get <ap0-ip>` check shows `local ... dev lo`, so the phone can reach its own hotspot address at L3; only the framework's client-network gate cares about the association.

The HAL dump confirms the combinations:

```text
{maxIfaces=2, types=[0]}                            -> 2 x STA
{maxIfaces=1, types=[2]}                            -> 1 x P2P
{maxIfaces=1, types=[0]}, {maxIfaces=1, types=[1]}  -> STA + AP
{maxIfaces=1, types=[0]}, {maxIfaces=1, types=[3]}  -> STA + NAN
{maxIfaces=1, types=[0]}, {maxIfaces=1, types=[4]}  -> STA + AP_BRIDGED
```

There is no AP + AP, no AP + P2P, no 2-STA + AP, and no same-device GO + GC.
`cmd wifi` cannot help either: `start-softap`, `set-multi-internet-mode` and
`instant-tethering` exist but are gated to `uid == ROOT_UID`, and this build has
no `su`. Android does not block the attempt (a `WifiNetworkSpecifier` with an
explicit BSSID or `cmd wifi connect-network ... -b <own-bssid>` is shell-legal),
so the limit is the PHY/firmware, not a permission. A test will simply time out.

## The Wireless debugging gate is also real

With hotspot-only, `adb_wifi_enabled` cannot be forced on. Writing it is
reverted within seconds:

```text
$ adb shell settings put global adb_wifi_enabled 1
$ adb shell settings get global adb_wifi_enabled
0
```

The platform log explains why:

```text
AdbDebuggingManager: Not connected to any wireless network. Not enabling adbwifi.
```

Keeper logs `enabled wireless debugging`, then observes the setting turned off
again. The gate is a real `TRANSPORT_WIFI` client network, not a UI quirk. This
is why "hotspot + wireless debugging" cannot replace the current AP for the
toggle; only legacy `5555` works over the hotspot.

## The short bootstrap that works

The external AP does not have to stay. Join a temporary STA once, let Keeper set
`adb_wifi_enabled=1` and pin `5555` over its TLS key, then remove the AP. The
toggle reverts to `0` and the TLS listener dies, but `service.adb.tcp.port=5555`
and the legacy listener survive. Turn on the phone hotspot and the laptop can
`adb connect <ap0>:5555` with no STA at all.

Keeper's log is the proof that the phone did the pin, not the host:

```text
WifiDebug: info pin.tls_ok asked adbd over TLS to listen on 5555
WifiDebug: pin check: closed (asked the app's TLS client)
WifiDebug: pin.state listening on 5555
```

This is the workflow to use when a short external AP is acceptable. The full
sequence, timings and boundaries are in
[`docs/short-bootstrap.md`](short-bootstrap.md); the transcript is
`tests/fixtures/short-bootstrap.log`. USB does **not** substitute for the STA:
with USB attached and no client link the same setting still reverts (see
`docs/usb-ideas.md`).

## Safe falsification of the self-connect idea (issue #2)

Keep USB adb attached and the rollback ready. This changes two settings and is
reversible.

1. Enable the hotspot (2.4 GHz is a different band from the current link).
2. Record `cmd wifi status`, `dumpsys wifi`, `ip -o link`, and
   `cmd wifi list-scan-results`.
3. Try joining the phone's own SSID:
   `cmd wifi connect-network "<ssid>" wpa2 "<pass>" -d -b <own-bssid>`
   (`-d` disables autojoin so the attempt is explicit; pin the BSSID from the
   SoftAP config).
4. Watch `dumpsys wifi` and logcat for `CMD_START_CONNECT`, `ASSOCIATED`,
   `NETWORK_CONNECTION_FAILURE`. Expect retries and no association.
5. Roll back: `cmd wifi forget-network <id>` if a profile was added, then
   rejoin the previous network and run `phone-doctor`.

If it ever associates and gets an IP, the physics argument is wrong; record the
interface, IP and whether `adb connect` works. Otherwise close #2 as ruled out.

## Cold-boot conclusion (no root)

The hotspot link and the short bootstrap are proven. A cold boot still loses
everything: `service.adb.tcp.port` is empty, no `5555` listener exists,
`adb_wifi_enabled` is `0`, the hotspot is off, and Termux is not running.
Without a temporary STA, USB host or root, there is no way to restore `5555` or
turn on Wireless debugging from the hotspot alone. Keeper is not idle after the
reboot, it is gated: four minutes of samples produced nothing, and when a
trusted STA appeared the toggle and `5555` were back in ~17 s with no host
command (`tests/fixtures/cold-boot-5555-timeline.log`). Getting a STA up without
an AP remains the open problem. See `ROADMAP.md`,
`docs/short-bootstrap.md` and issues #3, #12, #14, #15. Do not call a
one-pin-per-boot workflow automatic.
