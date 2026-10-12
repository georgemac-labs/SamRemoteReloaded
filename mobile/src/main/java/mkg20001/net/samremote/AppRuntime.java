package mkg20001.net.samremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import mkg20001.net.samremotecommon.ClientIdentity;
import mkg20001.net.samremotecommon.DeviceDiscovery;
import mkg20001.net.samremotecommon.LegacyTransport;
import mkg20001.net.samremotecommon.RemoteController;
import mkg20001.net.samremotecommon.TizenTransport;
import mkg20001.net.samremotecommon.TvDevice;

/** Phone surfaces share a selected TV and session, never independent competing pairing attempts. */
final class AppRuntime {
    private static AppRuntime instance;
    final RemoteController controller;
    final Handler main = new Handler(Looper.getMainLooper());
    private final Set<RemoteController.Observer> owners = new HashSet<>();
    private final ConnectivityManager connectivity;
    private final ClientIdentity clientIdentity;
    private String networkAddress;
    private Network wifiNetwork;
    private boolean watching;

    static synchronized AppRuntime get(Context context) {
        if (instance == null) instance = new AppRuntime(context.getApplicationContext());
        return instance;
    }

    private AppRuntime(Context context) {
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        SharedPreferences preferences = context.getSharedPreferences("mkg20001.net.samremote.settings", Context.MODE_PRIVATE);
        String clientId = preferences.getString("client_id", null);
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            preferences.edit().putString("client_id", clientId).apply();
        }
        DeviceDiscovery.Subnet subnet = wifiSubnet();
        clientIdentity = new ClientIdentity(subnet == null ? "0.0.0.0" : subnet.address,
                clientId, context.getString(R.string.app_name));
        controller = new RemoteController(new RemoteController.Store() {
            public TvDevice load() {
                String host = preferences.getString("tv_host", preferences.getString("last_ip", ""));
                if (!DeviceDiscovery.isIpv4(host) || host.equals("127.0.0.1")) return null;
                TvDevice.Protocol protocol;
                try { protocol = TvDevice.Protocol.valueOf(preferences.getString("tv_protocol", "LEGACY")); }
                catch (IllegalArgumentException e) { protocol = TvDevice.Protocol.LEGACY; }
                return new TvDevice(host, preferences.getString("tv_id", host), preferences.getString("tv_name", host),
                        protocol, preferences.getInt("tv_port", protocol == TvDevice.Protocol.TIZEN ? 8002 : 55000),
                        preferences.getString("tv_token", ""));
            }

            public void save(TvDevice device) {
                preferences.edit().putString("tv_host", device.host).putString("last_ip", device.host)
                        .putString("tv_id", device.id).putString("tv_name", device.name)
                        .putString("tv_protocol", device.protocol.name()).putInt("tv_port", device.port)
                        .putString("tv_token", device.token).apply();
            }
        }, device -> device.protocol == TvDevice.Protocol.LEGACY ? new LegacyTransport() : new TizenTransport(),
                clientIdentity, command -> main.post(command));
    }

    void connect(TvDevice device) {
        DeviceDiscovery.Subnet subnet = wifiSubnet();
        if (subnet == null) return;
        controller.setClientIdentity(new ClientIdentity(subnet.address, clientIdentity.id, clientIdentity.name));
        controller.connect(device);
    }

    boolean isRemoteVisible() {
        for (RemoteController.Observer owner : owners) if (owner instanceof Remote) return true;
        return false;
    }

    void acquire(RemoteController.Observer observer) {
        main.removeCallbacks(releaseIfUnused);
        if (!owners.add(observer)) return;
        controller.addObserver(observer);
        if (!watching) {
            DeviceDiscovery.Subnet subnet = wifiSubnet();
            networkAddress = subnet == null ? null : subnet.address;
            wifiNetwork = currentWifiNetwork();
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback);
            watching = true;
        }
    }

    void release(RemoteController.Observer observer) {
        if (!owners.remove(observer)) return;
        controller.removeObserver(observer);
        if (owners.isEmpty()) {
            // Keep the session across rotation and tile-to-activity handoff, then close unused resources.
            main.removeCallbacks(releaseIfUnused);
            main.postDelayed(releaseIfUnused, 500);
        }
    }

    DeviceDiscovery.Subnet wifiSubnet() {
        Network network = currentWifiNetwork();
        if (network == null) return null;
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties == null) return null;
        for (LinkAddress link : properties.getLinkAddresses()) {
            if (link.getAddress() instanceof Inet4Address) {
                return new DeviceDiscovery.Subnet(link.getAddress().getHostAddress(), link.getPrefixLength());
            }
        }
        return null;
    }

    private Network currentWifiNetwork() {
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties == null) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet4Address) {
                    return network;
                }
            }
        }
        return null;
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        public void onAvailable(Network network) { networkChanged(); }
        public void onLost(Network network) {
            main.post(() -> { if (watching) controller.disconnect(); });
            networkChanged();
        }
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) { networkChanged(); }
        private void networkChanged() {
            main.post(() -> {
                if (!watching) return;
                DeviceDiscovery.Subnet subnet = wifiSubnet();
                String address = subnet == null ? null : subnet.address;
                Network network = currentWifiNetwork();
                if (!java.util.Objects.equals(networkAddress, address) || !java.util.Objects.equals(wifiNetwork, network)) {
                    networkAddress = address;
                    wifiNetwork = network;
                    controller.disconnect();
                }
            });
        }
    };

    private final Runnable releaseIfUnused = this::releaseUnusedSession;

    private void releaseUnusedSession() {
        if (!owners.isEmpty()) return;
        controller.disconnect();
        if (watching) connectivity.unregisterNetworkCallback(networkCallback);
        watching = false;
    }
}
