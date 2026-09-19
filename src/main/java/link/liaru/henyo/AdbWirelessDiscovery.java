package link.liaru.henyo;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Short-lived discovery of the device's own Wireless debugging endpoints. */
final class AdbWirelessDiscovery {
    private static final String CONNECT_SERVICE = "_adb-tls-connect._tcp";
    private static final String PAIRING_SERVICE = "_adb-tls-pairing._tcp";
    private static final long CACHE_TTL_MS = 5_000L;
    private static final long DISCOVERY_TIMEOUT_MS = 1_500L;

    private final Context context;
    private final Object lock = new Object();
    private Result cached;
    private long cachedAt;

    AdbWirelessDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    Result discover() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (cached != null && now - cachedAt <= CACHE_TTL_MS) return cached;
            Result result = performDiscovery();
            cached = result;
            cachedAt = now;
            return result;
        }
    }

    private Result performDiscovery() {
        Object service = context.getSystemService(Context.NSD_SERVICE);
        if (!(service instanceof NsdManager)) return Result.error("nsd_unavailable");
        NsdManager nsd = (NsdManager) service;
        Set<String> localAddresses = localAddresses();
        DiscoveryRun run = new DiscoveryRun(nsd, localAddresses);
        WifiManager.MulticastLock multicastLock = null;
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("henyo-adb-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            run.start(CONNECT_SERVICE);
            run.start(PAIRING_SERVICE);
            run.await();
            return run.result();
        } catch (RuntimeException error) {
            return Result.error("nsd_error");
        } finally {
            run.stopAll();
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        }
    }

    private static Set<String> localAddresses() {
        Set<String> addresses = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> values = network.getInetAddresses();
                while (values.hasMoreElements()) {
                    InetAddress address = values.nextElement();
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // An empty set means discovery can still be reported, but not self-matched.
        }
        return addresses;
    }

    static final class Result {
        final boolean ok;
        final boolean available;
        final int connectPort;
        final int pairingPort;
        final boolean pairingMode;
        final String reason;

        private Result(boolean ok, boolean available, int connectPort, int pairingPort,
                       boolean pairingMode, String reason) {
            this.ok = ok;
            this.available = available;
            this.connectPort = connectPort;
            this.pairingPort = pairingPort;
            this.pairingMode = pairingMode;
            this.reason = reason;
        }

        static Result error(String reason) {
            return new Result(false, false, 0, 0, false, reason);
        }

        static Result of(int connectPort, int pairingPort) {
            return new Result(true, connectPort > 0, connectPort, pairingPort,
                    pairingPort > 0, connectPort > 0 ? "" : "wireless_debugging_disabled");
        }
    }

    private static final class DiscoveryRun {
        private final NsdManager nsd;
        private final Set<String> localAddresses;
        private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
        private volatile int connectPort;
        private volatile int pairingPort;

        DiscoveryRun(NsdManager nsd, Set<String> localAddresses) {
            this.nsd = nsd;
            this.localAddresses = localAddresses;
        }

        void start(String type) {
            NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String serviceType) {}
                @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}
                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            InetAddress host = info.getHost();
                            if (host == null || !localAddresses.contains(host.getHostAddress())) return;
                            if (type.equals(CONNECT_SERVICE)) connectPort = info.getPort();
                            if (type.equals(PAIRING_SERVICE)) pairingPort = info.getPort();
                        }
                    });
                }
                @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}
                @Override public void onDiscoveryStopped(String serviceType) {}
                @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
                @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            };
            listeners.add(listener);
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
        }

        void await() {
            try {
                Thread.sleep(DISCOVERY_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        void stopAll() {
            for (NsdManager.DiscoveryListener listener : listeners) {
                try {
                    nsd.stopServiceDiscovery(listener);
                } catch (RuntimeException ignored) {}
            }
        }

        Result result() {
            return Result.of(connectPort, pairingPort);
        }
    }
}
