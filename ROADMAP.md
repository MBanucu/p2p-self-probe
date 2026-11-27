# Roadmap

## Goal

Make the phone reachable over adb **automatically**, with the phone hosting the
link and **no external hardware**: no router, no travel AP, no USB Wi-Fi dongle,
no USB cable. The laptop is the adb client, not an access point. The phone may
host a hotspot or a Wi-Fi Direct group; the laptop joins it.

Hard constraints, by decision:

- **No root, no bootloader unlock, no custom kernel.** Root is not a fallback.
- Keep the current working link until a replacement is proven.
- Do not present an unproven path as "automatic"; the cold-boot behavior is the
  acceptance test.

## The two meanings of "wireless debugging"

1. **The Android toggle** (`adb_wifi_enabled`): requires a real Wi-Fi **client**
   network (`TRANSPORT_WIFI`). A phone-hosted hotspot or P2P GO does not satisfy
   it. Measured: `AdbDebuggingManager: Not connected to any wireless network.
   Not enabling adbwifi.` and the setting is reverted within seconds.
2. **The transport** (legacy `5555`): `adbd` binds the wildcard address. Once
   `service.adb.tcp.port=5555` is set, it works over `ap0`/`p2p-p2p0-*` and over
   USB, even with the toggle off. Measured: `adb connect 10.31.180.89:5555`
   succeeds over the phone's hotspot.

The project therefore targets the transport first. The toggle is only reachable
with a real STA link (the laptop AP or a router), which is not "no external
hardware".

## Status

| | Finding | Evidence | Status |
|---|---|---|---|
| 1 | The framework masks the local P2P address as `02:00:00:00:00:00`. | Probe run 3; `tests/fixtures/run3.log` | **Proven** |
| 2 | Self-P2P is impossible: 0 peers, `connect(self)` and `connect(own group)` fail. | Run 3 | **Ruled out** |
| 3 | `createGroup()` works as GO with 0 clients; the group is `DIRECT-KL-XPLORE 2`. | Run 3 | **Proven** |
| 4 | A P2P GO coexists with a **client** STA link for a short run. | Run 3: `client true/true/true`; laptop station 1961 -> 1990 s | **Proven (short)** |
| 5 | Same-radio hotspot self-connect is a PHY limit; AP and STA share one radio. | `docs/hotspot.md`; issue #2 | **Ruled out by analysis** |
| 6 | Phone hotspot works: `ap0` is randomized per boot (`10.31.180.89`, then `10.231.103.217`), laptop joins, `adb <ap0>:5555` OK. | live 2026-11-27; `tests/fixtures/hotspot-subnet.log` | **Proven** |
| 7 | Writing `adb_wifi_enabled=1` on hotspot-only or USB-only is reverted by the platform. | `AdbDebuggingManager`; `tests/fixtures/usb-self-adb.log` | **Proven** |
| 8 | Shell cannot set `service.adb.tcp.port` or `persist.adb.tcp.port`. | `Failed to set property` as uid 2000 | **Proven** |
| 9 | `5555` survives hotspot/P2P transitions and STA loss, but not a reboot. | `tests/fixtures/short-bootstrap.log` | **Proven** |
| 10 | Short bootstrap works: a temporary STA lets Keeper pin `5555`; the STA can then go away. | `docs/short-bootstrap.md` | **Proven** |
| 11 | Cold boot loses `5555`, the toggle and the hotspot; the bootstrap must be repeated. | `tests/fixtures/cold-boot.log` | **Open blocker** |
| 12 | P2P GO cannot coexist with the hotspot; with the hotspot off the default GO is 6 GHz, invisible to the 8265. | runs 4/5; `tests/fixtures/p2p-*.log` | **Blocked** |
| 13 | Termux can maintain an existing listener but cannot create the first one; USB is not a substitute. | `docs/termux.md`, `docs/usb-ideas.md` | **Ruled out** |
| 14 | Hotspot and rndis subnets are randomized per boot; read `ap0`/`rndis0` each time. | `tests/fixtures/hotspot-subnet.log` | **Proven** |

## Tracks

### A. Keeper + real AP — the working automatic path (but external AP)

**Issues:** [#3](https://github.com/MBanucu/p2p-self-probe/issues/3),
[#13](https://github.com/MBanucu/p2p-self-probe/issues/13)

Wireless ADB Keeper already switches the toggle on when a real STA link exists,
pins `5555` via its trusted TLS key, and survives reboots. This is the only
"automatic" path proven today. It needs an AP, so it does not meet the
no-external-hardware goal; it is the control and the fallback.

### B. P2P GO + laptop as station — blocked by AP+P2P and the default 6 GHz

**Issue:** [#1](https://github.com/MBanucu/p2p-self-probe/issues/1)

Phone owns a P2P group; laptop joins `DIRECT-…` as a normal WPA2 station; adb
runs over `5555` on the GO interface. Measured blockers: with the hotspot up
the group cannot be created (`Failed to setup interface for P2P`); with the
hotspot off the GO ran on **6135 MHz (6 GHz)** and the laptop's 8265 could not
see it. Next, if pursued: force a 5 GHz group with
`WifiP2pConfig.Builder().setGroupOperatingFrequency(5180)`, then join from the
laptop and test the lease, mDNS, screen-off lifetime and laptop reboot. It is
not needed for the `5555` bootstrap.

### C. Hotspot + short bootstrap — proven, one temporary STA per boot

**Issues:** [#3](https://github.com/MBanucu/p2p-self-probe/issues/3),
[#12](https://github.com/MBanucu/p2p-self-probe/issues/12),
[#19](https://github.com/MBanucu/p2p-self-probe/issues/19)

The phone-hosted link is proven: `adb <ap0>:5555` works with
`adb_wifi_enabled=0`. To create `5555`, join a temporary STA once, let Keeper
set the toggle and pin the port, then remove the AP; the legacy listener
survives. This is [`docs/short-bootstrap.md`](docs/short-bootstrap.md). The
remaining gap is the cold boot: `5555`, the toggle and the hotspot are all gone,
so the bootstrap must be repeated. Read `ap0` every boot; the subnet is
randomized.

### D. Termux on-device watchdog

**Issues:** [#14](https://github.com/MBanucu/p2p-self-probe/issues/14),
[#15](https://github.com/MBanucu/p2p-self-probe/issues/15)

Measured: Termux can connect to `127.0.0.1:5555` and re-send `tcpip:5555` once a
listener exists, but it cannot create the first one. With USB attached its
`adb devices` still shows no phone, a closed port is refused, and
`/dev/socket/adbd` is permission-denied. It is a watchdog, not a bootstrap.

### E. No-root persistence of legacy 5555

**Issue:** [#12](https://github.com/MBanucu/p2p-self-probe/issues/12)

Tested the remaining no-root mechanisms. Keeper's TLS pin over a temporary STA
works and survives losing the STA; a host `adb tcpip` is cable-bound; Termux
cannot create the first listener; Shizuku `setprop` fails like shell; a cold
boot has no listener. Acceptance is still a cold-boot-to-adb path. The short
bootstrap covers the same-boot case; the cold-boot case is open.

### F. Keeper gate: can Wireless debugging be enabled without a client network?

**Issue:** [#11](https://github.com/MBanucu/p2p-self-probe/issues/11)

Answered **no** on this phone. `AdbDebuggingManager` requires a
`TRANSPORT_WIFI` network; a hotspot, P2P GO, USB or rndis does not count. With
USB attached and no STA the toggle still reverts. There is no supported fake:
the P2P client has no NetworkAgent, NAN is `WIFI_AWARE`, and a VPN is VPN.

### I. USB self-adb / fake attach — ruled out without root

**Issue:** [#20](https://github.com/MBanucu/p2p-self-probe/issues/20)

The idea: make Android think a USB host is attached, then use adb from Termux to
run `tcpip:5555`. Measured no. USB debugging being on does not give Termux a
device; it sees no phone and a closed port is refused. `/dev/socket/adbd` is
denied. The UDC/configfs are root-only, `cmd usb` has no shell implementation,
and one UDC/port cannot be host and device. Even a faked attach would leave
adbd in USB mode with no TCP listener. See `docs/usb-ideas.md`.

### G. Hotspot self-connect falsification

**Issue:** [#2](https://github.com/MBanucu/p2p-self-probe/issues/2)

The user's original hotspot-as-self idea. Analysis says impossible (one radio,
half-duplex); the issue is the cheap, reversible test that either confirms it or
overturns the physics argument. Low priority now that the platform gate (#11)
also blocks the toggle.

### H. Probe, tests and documentation

**Issues:** [#8](https://github.com/MBanucu/p2p-self-probe/issues/8),
[#9](https://github.com/MBanucu/p2p-self-probe/issues/9),
[#10](https://github.com/MBanucu/p2p-self-probe/issues/10)

Keep the P2P evidence reproducible: real-MAC override, hold, STA detection,
`ProbeLogic` tests, the run-3 fixture, and docs for every finding.

## Decision log

- **No root.** The persistent-property and USB-loopback paths are out. Issues
  #6, #7 and #16 are closed for that reason.
- **Wireless debugging toggle requires a real STA.** Hotspot/P2P GO/USB cannot
  turn it on; `adb_wifi_enabled` is reverted by the platform.
- **A temporary STA is an acceptable bootstrap.** It is needed only until Keeper
  pins `5555`; after that the hotspot carries adb and the AP can go away. This is
  not full automation: a cold boot needs the bootstrap again.
- **Legacy 5555 is the usable transport.** It works over the phone hotspot,
  survives losing the STA and a hotspot toggle, but not a reboot; shell cannot
  set the property.
- **Termux maintains; it does not bootstrap.** It needs an existing TCP
  listener; it cannot create one after a cold boot, and USB does not give it one.
- **USB is not a shortcut.** USB debugging being enabled does not satisfy the
  toggle gate, and Termux cannot use the phone's own USB endpoint. Faking the
  attach is root-only and would not create a TCP listener anyway.
- **Read the addresses.** The hotspot and rndis subnets are randomized per boot;
  `10.31.180.89` and `192.168.49.1` are examples, not constants.
- **The hotspot + short bootstrap is the working no-external-AP result.**
  Full automation is blocked on the cold-boot listener, not on the link.

## Order of work

1. **#3/#12**: cold-boot the hotspot with USB attached, measure the missing
   listener and hotspot, and automate or explicitly time the bootstrap.
2. **#14/#15**: use Termux only as a watchdog after a pin; do not expect it to
   create the first listener.
3. **#1**: force a 5 GHz P2P group and retry the laptop-as-station path; it is
   optional now that the hotspot + bootstrap works.
4. **#11**: close the "fake Wi-Fi" question with the USB evidence; document the
   toggle limit in Keeper.
5. **#2**: run the reversible hotspot self-connect falsification (low priority).
6. **#8/#9/#10**: keep docs, fixtures and tests current.

## Non-goals

- Rooting, unlocking the bootloader, or a custom kernel.
- A USB Wi-Fi dongle, USB self-loopback, or a second radio that needs root.
- Faking USB attach or a virtual UDC without root.
- Making the Android Wireless debugging toggle accept a phone-hosted AP/GO or
  a USB connection.
- Self-P2P or STA-to-own-SoftAP: both are one-radio self-association.
- Calling a one-pin-per-boot workflow "automatic".
