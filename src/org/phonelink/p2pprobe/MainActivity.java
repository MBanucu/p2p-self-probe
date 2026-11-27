package org.phonelink.p2pprobe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.WpsInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * A single-run probe of Wi-Fi Direct self-connection.
 *
 * <p>It discovers peers, tries to connect to its own P2P address, then creates
 * a group and tries to connect to that group's owner (itself). Every step and
 * every failure reason is written to logcat and to the screen, and the SSID is
 * logged before, during and after the group so the STA effect is visible.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "P2PSelfTest";
    private static final int REQ_NEARBY = 7;
    private static final long DISCOVERY_MS = 8000;
    private static final long SETTLE_MS = 2500;
    private static final long CONNECT_WAIT_MS = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder transcript = new StringBuilder();

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private TextView output;

    private String ownAddress = "";
    private boolean selfInPeers;
    private int peerCount = -1;
    private String staBefore = "";
    private String staDuring = "";
    private String staAfter = "";
    private String selfConnect = "not attempted";
    private String groupConnect = "not attempted";
    private String groupResult = "not attempted";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                log("broadcast: p2p state " + (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED ? "enabled" : "disabled(" + state + ")"));
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                log("broadcast: peers changed");
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                log("broadcast: p2p network " + (info == null ? "?" : info.toString()));
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (device != null) {
                    log("broadcast: this device " + device.deviceName + " " + device.deviceAddress);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        output = new TextView(this);
        output.setGravity(Gravity.START);
        output.setTextIsSelectable(true);
        output.setPadding(24, 24, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        setContentView(scroll);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);

        log("device: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            log("asking for NEARBY_WIFI_DEVICES");
            requestPermissions(new String[] { Manifest.permission.NEARBY_WIFI_DEVICES }, REQ_NEARBY);
            return;
        }
        startProbe();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_NEARBY) {
            return;
        }
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        log("NEARBY_WIFI_DEVICES granted: " + granted);
        startProbe();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        super.onDestroy();
    }

    // -- the probe -----------------------------------------------------

    private void startProbe() {
        manager = getSystemService(WifiP2pManager.class);
        if (manager == null) {
            log("FAIL: no WifiP2pManager service");
            finishProbe();
            return;
        }
        channel = manager.initialize(this, getMainLooper(), null);
        if (channel == null) {
            log("FAIL: no P2P channel (is Wi-Fi on?)");
            finishProbe();
            return;
        }
        WifiManager wifi = getSystemService(WifiManager.class);
        log("wifi enabled: " + (wifi != null && wifi.isWifiEnabled()));
        staBefore = ssid();
        log("sta before: " + staBefore);

        manager.requestDeviceInfo(channel, device -> {
            if (device == null) {
                log("requestDeviceInfo: null");
            } else {
                ownAddress = device.deviceAddress == null ? "" : device.deviceAddress;
                log("this device: " + device.deviceName + " " + ownAddress);
            }
            discover();
        });
    }

    private void discover() {
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log("discoverPeers: started");
                handler.postDelayed(MainActivity.this::stopDiscovery, DISCOVERY_MS);
            }

            @Override
            public void onFailure(int reason) {
                log("discoverPeers: failed " + reasonName(reason));
                stopDiscovery();
            }
        });
    }

    private void stopDiscovery() {
        manager.stopPeerDiscovery(channel, action("stopPeerDiscovery"));
        manager.requestPeers(channel, this::onPeers);
        handler.postDelayed(this::connectToSelf, SETTLE_MS);
    }

    private void onPeers(WifiP2pDeviceList peers) {
        selfInPeers = false;
        peerCount = 0;
        if (peers == null) {
            log("peers: none (null list)");
            return;
        }
        for (WifiP2pDevice device : peers.getDeviceList()) {
            peerCount++;
            boolean self = ownAddress != null && ownAddress.equalsIgnoreCase(device.deviceAddress);
            if (self) {
                selfInPeers = true;
            }
            log("peer: " + device.deviceName + " " + device.deviceAddress + (self ? "  <-- SELF" : ""));
        }
        log("peers: " + peerCount + " total, self listed: " + selfInPeers);
    }

    private void connectToSelf() {
        manager.requestDeviceInfo(channel, device -> {
            if (device != null && device.deviceAddress != null && !device.deviceAddress.isEmpty()) {
                ownAddress = device.deviceAddress;
            }
            log("connect(self): address " + ownAddress);
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = ownAddress;
            config.wps.setup = WpsInfo.PBC;
            try {
                manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        selfConnect = "accepted";
                        log("connect(self): accepted (unexpected)");
                        handler.postDelayed(MainActivity.this::afterSelfConnect, CONNECT_WAIT_MS);
                    }

                    @Override
                    public void onFailure(int reason) {
                        selfConnect = "failed " + reasonName(reason);
                        log("connect(self): failed " + reasonName(reason));
                        handler.postDelayed(MainActivity.this::createGroup, SETTLE_MS);
                    }
                });
            } catch (RuntimeException exc) {
                selfConnect = "threw " + exc;
                log("connect(self): threw " + exc);
                handler.postDelayed(this::createGroup, SETTLE_MS);
            }
        });
    }

    private void afterSelfConnect() {
        manager.requestConnectionInfo(channel, info -> {
            log("connect(self) info: " + info(info));
            manager.removeGroup(channel, action("removeGroup after self connect"));
            handler.postDelayed(this::createGroup, SETTLE_MS);
        });
    }

    private void createGroup() {
        manager.cancelConnect(channel, action("cancelConnect"));
        handler.postDelayed(() -> manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                groupResult = "success";
                log("createGroup: success");
                handler.postDelayed(MainActivity.this::describeGroup, 1500);
            }

            @Override
            public void onFailure(int reason) {
                groupResult = "failed " + reasonName(reason);
                log("createGroup: failed " + reasonName(reason));
                finishProbe();
            }
        }), SETTLE_MS);
    }

    private void describeGroup() {
        manager.requestGroupInfo(channel, group -> {
            if (group == null) {
                log("group: null");
            } else {
                log("group owner: " + group.isGroupOwner());
                log("group ssid: " + group.getNetworkName());
                log("group passphrase: " + (group.getPassphrase() == null ? "-" : group.getPassphrase()));
                log("group interface: " + group.getInterface());
                if (group.getOwner() != null) {
                    log("group owner device: " + group.getOwner().deviceName + " " + group.getOwner().deviceAddress);
                }
                log("group clients: " + group.getClientList().size());
            }
            staDuring = ssid();
            log("sta during: " + staDuring);
            logInterfaces();
            connectToGroupOwner();
        });
    }

    private void connectToGroupOwner() {
        manager.requestGroupInfo(channel, group -> {
            String address = ownAddress;
            if (group != null && group.getOwner() != null && group.getOwner().deviceAddress != null) {
                address = group.getOwner().deviceAddress;
            }
            log("connect(own group): address " + address);
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = address;
            config.wps.setup = WpsInfo.PBC;
            try {
                manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        groupConnect = "accepted";
                        log("connect(own group): accepted (unexpected)");
                        handler.postDelayed(() -> manager.requestConnectionInfo(channel, info -> {
                            log("connect(own group) info: " + info(info));
                            removeGroup();
                        }), CONNECT_WAIT_MS);
                    }

                    @Override
                    public void onFailure(int reason) {
                        groupConnect = "failed " + reasonName(reason);
                        log("connect(own group): failed " + reasonName(reason));
                        removeGroup();
                    }
                });
            } catch (RuntimeException exc) {
                groupConnect = "threw " + exc;
                log("connect(own group): threw " + exc);
                removeGroup();
            }
        });
    }

    private void removeGroup() {
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log("removeGroup: success");
                finishProbe();
            }

            @Override
            public void onFailure(int reason) {
                log("removeGroup: failed " + reasonName(reason));
                finishProbe();
            }
        });
    }

    private void finishProbe() {
        staAfter = ssid();
        log("sta after: " + staAfter);
        log("RESULT self in peer list: " + selfInPeers + " (" + peerCount + " peers)");
        log("RESULT connect to self: " + selfConnect);
        log("RESULT createGroup: " + groupResult);
        log("RESULT connect to own group: " + groupConnect);
        boolean staKept = !staBefore.isEmpty() && staBefore.equals(staDuring) && staBefore.equals(staAfter);
        log("RESULT sta link kept through the group: " + staKept + " (" + staBefore + " / " + staDuring + " / " + staAfter + ")");
        String verdict;
        if (selfInPeers || selfConnect.equals("accepted") || groupConnect.equals("accepted")) {
            verdict = "self P2P may be possible; inspect the log";
        } else {
            verdict = "self P2P is not possible: the phone cannot discover or connect to itself";
        }
        log("VERDICT " + verdict);
        writeTranscript();
    }

    // -- helpers -------------------------------------------------------

    private String ssid() {
        WifiManager wifi = getSystemService(WifiManager.class);
        if (wifi == null) {
            return "";
        }
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || info.getSSID() == null || "<unknown ssid>".equals(info.getSSID())) {
            return "";
        }
        String ssid = info.getSSID();
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private void logInterfaces() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = iface.getName();
                if (!name.startsWith("p2p") && !"wlan0".equals(name) && !"ap0".equals(name)) {
                    continue;
                }
                for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                    log("iface " + name + " " + address.getHostAddress());
                }
            }
        } catch (Exception exc) {
            log("iface scan failed: " + exc);
        }
    }

    private WifiP2pManager.ActionListener action(String label) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log(label + ": success");
            }

            @Override
            public void onFailure(int reason) {
                log(label + ": failed " + reasonName(reason));
            }
        };
    }

    private static String info(WifiP2pInfo info) {
        if (info == null) {
            return "null";
        }
        return "groupFormed=" + info.groupFormed + " isGroupOwner=" + info.isGroupOwner + " owner=" + info.groupOwnerAddress;
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case WifiP2pManager.ERROR:
                return "ERROR";
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "P2P_UNSUPPORTED";
            case WifiP2pManager.BUSY:
                return "BUSY";
            default:
                return "reason " + reason;
        }
    }

    private void log(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + line;
        Log.i(TAG, line);
        transcript.append(stamped).append('\n');
        if (output != null) {
            output.append(stamped + "\n");
        }
    }

    private void writeTranscript() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File file = new File(dir, "result.txt");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(transcript.toString());
            }
            log("wrote " + file);
        } catch (Exception exc) {
            log("write failed: " + exc);
        }
    }
}
