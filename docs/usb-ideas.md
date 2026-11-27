# USB ideas and the no-root boundary

Measured on 2026-11-27. This page records what USB can do for adb, and why the
"USB to itself / virtual USB port" idea is out of scope without root.

## What works now: USB is already a TCP transport

The phone enumerates as `rndis,adb`, with `rndis0` and the laptop's
`enp55s0u2` on the same link. The subnet changes per boot/connection
(`10.19.134.0/24` in one run, `172.28.111.0/24` after a reboot), so read
`rndis0` rather than hardcoding it. Because `adbd` binds the wildcard address,
this works with no Wi-Fi at all:

```sh
ip=$(adb shell ip -4 -o addr show rndis0 | awk '{print $4}' | cut -d/ -f1)
adb connect "$ip:5555"
adb -s "$ip:5555" shell echo ok
# IPv6 link-local also works:
adb connect '[fe80::9c9c:eff:fe9a:3d28%enp55s0u2]:5555'
```

`avahi-browse -rt _adb._tcp` resolves `Android.local:5555`, so the per-boot
IPv4 churn can be avoided. The TLS listener is also wildcard-bound and accepts
the existing key over the USB net when Wireless debugging is on.

The catch is the boot composition: `persist.sys.usb.config=adb`, so `rndis0` is
not guaranteed at boot. Restoring it is a host-side step:

```sh
adb tcpip 5555
adb shell svc usb setFunctions rndis,adb
adb connect <phone-rndis-ip>:5555
```

This is a cable, so it is the recovery path, not the no-external-hardware goal.

## Why "USB to itself" does not work

- The phone has one UDC (`11201000.usb0`) and one USB-C port. DRP means one
  role at a time; it cannot be host and device simultaneously.
- `dummy_hcd`, `raw-gadget`, `gadgetfs` and `usbip` are not enabled in the
  kernel; configfs is root-only. A phone-side virtual host controller would
  need a custom kernel/module and root.
- Even a successful in-phone loopback would not give the laptop a route to the
  phone, so it would not solve the actual problem.
- There is no Android behavior that enables legacy `5555` merely because USB is
  attached. `service.adb.tcp.port` is set by `adb tcpip` (a host-side adbd
  service request), not by the USB gadget state.
- `adb shell setprop service.adb.tcp.port 5555` and
  `setprop persist.adb.tcp.port ...` both fail as uid 2000. Root would fix it;
  root is out of scope by decision.

## Faking USB attach: measured no

The latest idea was to make Android think a USB host is attached, so adbd would
be "on" and the phone could use adb locally through Termux. The measurements do
not support it.

### USB does not satisfy the Wireless debugging gate

With the cable attached (`dumpsys usb`: `connected=true`, `configured=true`,
`kernel_state=CONFIGURED`, `host_connected=false`) and **no** STA, forcing the
toggle still reverts:

```text
$ adb shell settings put global adb_wifi_enabled 1
$ adb shell settings get global adb_wifi_enabled
0
AdbDebuggingManager: Not connected to any wireless network. Not enabling adbwifi.
```

`5555` stays up because it was already pinned; USB plays no part in the gate.

### Termux still has no device

With USB attached and adbd running, Termux (uid 10514) sees no phone:

```text
$ adb kill-server; adb devices
List of devices attached
emulator-5554   offline

$ adb connect 127.0.0.1:5556
failed to connect to '127.0.0.1:5556': Connection refused

$ adb connect 127.0.0.1:5555     # already pinned by Keeper
connected to 127.0.0.1:5555

$ ls /dev/socket/adbd
ls: cannot access '/dev/socket/adbd': Permission denied
```

The USB transport belongs to adbd; Termux cannot open it. The only working
self-connection was the TCP listener Keeper had already created. A closed port
is refused. `/dev/socket/adbd` exists but is denied to both shell and Termux.

### The attach itself is root-only

- `/sys/class/udc/11201000.usb0/state` and `soft_connect` are permission-denied
  to shell; configfs is mounted at `/config` but empty and root-only.
- `cmd usb -h` returns `No shell command implementation`; `dumpsys usb` exposes
  `is_simulation_active=false`, but there is no shell entry point to set it.
- `svc usb` can only choose functions (`mtp`, `ptp`, `rndis`, `midi`, `ncm`),
  not attach a host or start a TCP listener.
- `dummy_hcd`, `raw-gadget`, `gadgetfs`, `usbip` and `vhci` are absent/root-only,
  and one UDC/port cannot be host and device at the same time.

Even a successful attach would leave adbd in USB mode with no TCP listener, and
Termux still could not use the USB endpoint. The missing piece is always the
first TCP listener: `tcpip:5555` over USB from a real host, or over the TLS key
after a real STA. See [`short-bootstrap.md`](short-bootstrap.md).

## No-root conclusion

USB can carry adb over TCP today, and it is useful for recovery and for the
one-time pin. It cannot be the automatic phone-hosted link after a cold boot
without a host on the other end, and it cannot make the Wireless debugging
toggle accept a hotspot/P2P interface. See issues #4, #5, #12, #14 and #15.

The root-only paths (USB loopback, USB Wi-Fi dongle, persistent property) are
closed in #6, #7 and #16 and are not part of the roadmap.
