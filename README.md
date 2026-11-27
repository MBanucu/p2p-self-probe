# p2p-self-probe

A throwaway Android app that answers two questions with evidence, not theory:

1. **Can the phone connect to itself over Wi-Fi Direct (P2P)?**
   - Does the phone's own P2P address appear in its peer list?
   - What happens when `WifiP2pManager.connect()` is given that address?
   - Does `connect()` work against a group the same phone just created?
2. **Does a P2P group-owner role coexist with a Wi-Fi client link?**
   - The phone is first brought onto a normal AP (the laptop's `PhoneLink`, an
     external AP, anything), then it calls `createGroup()`.
   - The probe records whether the group forms and whether the STA link stays
     connected while it exists.

## Why

The laptop-local AP is what keeps the phone in **client mode**, which is what
the Wireless debugging toggle needs. The idea of a phone-hosted link (hotspot or
P2P) is attractive because the AP would then survive a laptop reboot. The open
question is whether the phone can provide both ends of that link itself, or at
least run a P2P group without losing the client link. This app is the cheap
empirical check.

Expected (prior): a device cannot discover or connect to itself; `connect()` to
the local P2P address fails and `createGroup()` is the only way to make a group.
The interesting unknown is the second question.

## What it does, in order

1. Registers for the P2P broadcasts and logs the P2P enabled state.
2. `requestDeviceInfo` → logs the local P2P address.
3. `discoverPeers` for 8 s → logs every peer; marks the local address if it
   appears (it should not).
4. `connect()` to the local P2P address → logs success, or the failure reason.
5. `cancelConnect`, then `createGroup()` → logs the group (owner, SSID,
   passphrase, clients, interface addresses).
6. `connect()` to the group owner's address (the phone itself) → logs the
   result.
7. `removeGroup` → logs that the group is gone and whether the STA link is still
   connected.

Everything is written to logcat with the tag `P2PSelfTest` and to the screen.
The probe is read-only with respect to the STA link: it never disconnects Wi-Fi,
and it removes the group it creates.

## Build

The build has no Gradle and no AndroidX, like `phone-link-proxy`. The flake
provides the SDK and the JDK:

```sh
nix develop --command ./build.sh
# build/p2p-self-probe.apk
```

or hermetically:

```sh
nix build .#   # result/p2p-self-probe.apk
```

## Run

```sh
adb install -r build/p2p-self-probe.apk
adb shell pm grant org.phonelink.p2pprobe android.permission.NEARBY_WIFI_DEVICES
adb logcat -c
adb shell am start -n org.phonelink.p2pprobe/.MainActivity
sleep 25
adb logcat -d -s P2PSelfTest
```

The phone must be connected to a Wi-Fi network before step 5 if you want the
coexistence answer; the app logs the SSID before and after.

## Cost

Creating a P2P group can make the Wi-Fi driver switch channel and briefly drop
the STA link. On a phone that is somebody's daily uplink, run this with USB adb
attached so the rejoin watchdog can put the link back, and expect a few seconds
of interruption. The app removes its group at the end.

## Layout

- `src/org/phonelink/p2pprobe/MainActivity.java` — the whole probe.
- `AndroidManifest.xml`, `res/values/strings.xml` — a single launcher activity.
- `build.sh` — aapt2 + javac + d8 + zipalign + apksigner, no Gradle.
- `default.nix`, `flake.nix` — the SDK shell and the hermetic APK build.

## License

MIT. See `LICENSE`.
