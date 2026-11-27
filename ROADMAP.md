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
| 4 | A P2P GO coexists with the STA link for a short run. | Run 3: `client true/true/true`; laptop station 1961 -> 1990 s | **Proven (short)** |
| 5 | Same-radio hotspot self-connect is a PHY limit; AP and STA share one radio. | `docs/hotspot.md`; issue #2 | **Ruled out by analysis** |
| 6 | Phone hotspot works: `ap0` 10.31.180.89, laptop 10.31.180.149, `adb 10.31.180.89:5555` OK. | live 2026-11-27 | **Proven** |
| 7 | Writing `adb_wifi_enabled=1` on hotspot-only is reverted by the platform. | `AdbDebuggingManager`; issue #11 | **Proven** |
| 8 | Shell cannot set `service.adb.tcp.port` or `persist.adb.tcp.port`. | `Failed to set property` as uid 2000 | **Proven** |
| 9 | `5555` survives hotspot/P2P transitions, but not a reboot. | Keeper docs; issue #12 | **Known** |
| 10 | No-root automatic hotspot-only adb after a cold boot is currently unsolved. | 7+8+9 | **Open blocker** |
| 11 | P2P GO + laptop as a normal station is the next no-root wireless candidate. | `docs/direct-links.md`; issue #1 | **To test** |
| 12 | Termux can maintain/pin an existing listener, but cannot create one after boot. | issue #14/#15 | **Partial** |

## Tracks

### A. Keeper + real AP — the working automatic path (but external AP)

**Issues:** [#3](https://github.com/MBanucu/p2p-self-probe/issues/3),
[#13](https://github.com/MBanucu/p2p-self-probe/issues/13)

Wireless ADB Keeper already switches the toggle on when a real STA link exists,
pins `5555` via its trusted TLS key, and survives reboots. This is the only
"automatic" path proven today. It needs an AP, so it does not meet the
no-external-hardware goal; it is the control and the fallback.

### B. P2P GO + laptop as station — the main no-root wireless candidate

**Issue:** [#1](https://github.com/MBanucu/p2p-self-probe/issues/1)

Phone owns a P2P group; laptop joins `DIRECT-…` as a normal WPA2 station; adb
runs over `5555` on the GO interface. No root, no P2P stack on Linux, no
external AP. Open: read the GO address/lease (do not assume `192.168.49.1`),
mDNS on `p2p-p2p0-*`, screen-off lifetime, laptop reboot.

### C. Hotspot + laptop — proven, one pin per boot

**Issue:** [#3](https://github.com/MBanucu/p2p-self-probe/issues/3)

The current live topology. `adb 10.31.180.89:5555` works with
`adb_wifi_enabled=0`. The missing piece is making `5555` exist after a cold boot
without USB or a temporary STA. This is the most practical no-root path if the
pin can be automated; otherwise it is one `adb tcpip 5555` per boot.

### D. Termux on-device bootstrap and watchdog

**Issues:** [#14](https://github.com/MBanucu/p2p-self-probe/issues/14),
[#15](https://github.com/MBanucu/p2p-self-probe/issues/15)

Termux can run `adb` over loopback, pair, `adb tcpip 5555`, keep a wake lock and
reconnect at boot. It cannot write secure settings, start adbd, set properties,
or create a TCP listener when adbd has none. It is an ingredient, not a
bootstrap for the cold-boot hotspot case.

### E. No-root persistence of legacy 5555

**Issue:** [#12](https://github.com/MBanucu/p2p-self-probe/issues/12)

Test the remaining no-root mechanisms: Keeper TLS pin over a temporary STA, USB
`adb tcpip` once, Termux loopback, Shizuku `setprop` (expected to fail like
shell), and a hotspot cold boot with no listener. Acceptance is either a
cold-boot-to-adb path or a precise proof that none exists without root or an
external host.

### F. Keeper gate: can Wireless debugging be enabled without a client network?

**Issue:** [#11](https://github.com/MBanucu/p2p-self-probe/issues/11)

Measured no on this phone: `AdbDebuggingManager` rejects hotspot/P2P. Confirm
there is no supported fake `TRANSPORT_WIFI` (P2P client has no NetworkAgent,
NAN is `WIFI_AWARE`, VPN is VPN) and document the limit in Keeper's UI/docs.

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
- **Wireless debugging toggle requires a real STA.** Hotspot/P2P GO cannot turn
  it on; `adb_wifi_enabled` is reverted by the platform.
- **Legacy 5555 is the usable transport.** It works over the phone hotspot, but
  is volatile and shell cannot set it.
- **Termux maintains; it does not bootstrap.** It needs an existing TCP
  listener; it cannot create one after a cold boot.
- **The current hotspot + 5555 link is the working no-external-AP result.**
  Full automation is blocked on the cold-boot listener, not on the link.

## Order of work

1. **#3**: cold-boot the hotspot with USB attached, measure whether any TCP
   listener appears, and time the one-time `adb tcpip 5555` recovery.
2. **#1**: keep the P2P group up, join from the laptop, prove address + adb +
   laptop reboot with no external AP.
3. **#12/#14/#15**: test the no-root cold-boot matrix; Termux watchdog after
   `5555` exists.
4. **#11**: close the "fake Wi-Fi" question with evidence; document the toggle
   limit in Keeper.
5. **#2**: run the reversible hotspot self-connect falsification.
6. **#8/#9/#10**: land docs, tests and the probe hardening.

## Non-goals

- Rooting, unlocking the bootloader, or a custom kernel.
- A USB Wi-Fi dongle, USB self-loopback, or a second radio that needs root.
- Making the Android Wireless debugging toggle accept a phone-hosted AP/GO.
- Self-P2P or STA-to-own-SoftAP: both are one-radio self-association.
- Calling a one-pin-per-boot workflow "automatic".
