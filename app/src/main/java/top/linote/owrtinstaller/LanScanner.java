package top.linote.owrtinstaller;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Finds hosts with an open classic wireless-ADB port on the phone's local IPv4 networks. */
final class LanScanner {
    static final class Hit {
        final String host;
        final int port;
        final boolean gateway;
        Hit(String host, int port, boolean gateway) { this.host = host; this.port = port; this.gateway = gateway; }
        String endpoint() { return host + ":" + port; }
    }

    private LanScanner() {}

    /** Default gateways of the current networks (portable hotspots are usually the gateway). */
    static List<String> gateways(Context c) {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm == null) return out;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !(nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;  // never the carrier network
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress g = r.getGateway();
                    if (r.isDefaultRoute() && g instanceof Inet4Address && !g.isAnyLocalAddress()) {
                        String h = g.getHostAddress();
                        if (!out.contains(h)) out.add(h);
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** Candidate hosts: gateways first, then the /24 (or smaller) subnets of local site-local IPv4 addresses. */
    static List<String> candidates(Context c, List<String> gateways) {
        Set<String> hosts = new LinkedHashSet<>(gateways);
        Set<String> self = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !localLink(ni.getName())) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    self.add(a.getHostAddress());
                    int prefix = Math.max(24, Math.min(30, ia.getNetworkPrefixLength()));
                    byte[] b = a.getAddress();
                    int ip = (b[0] & 0xff) << 24 | (b[1] & 0xff) << 16 | (b[2] & 0xff) << 8 | (b[3] & 0xff);
                    int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
                    int net = ip & mask, size = 1 << (32 - prefix);
                    for (int i = 1; i < size - 1; i++) {
                        int h = net + i;
                        hosts.add(((h >>> 24) & 0xff) + "." + ((h >>> 16) & 0xff) + "." + ((h >>> 8) & 0xff) + "." + (h & 0xff));
                    }
                }
            }
        } catch (Exception ignored) { }
        hosts.removeAll(self);
        return new ArrayList<>(hosts);
    }

    /** Wi-Fi client, own hotspot / Wi-Fi Direct, Ethernet, USB / Bluetooth tethering; not cellular (rmnet, seth, sipa, ccmni ...). */
    static boolean localLink(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (String p : new String[]{"wlan", "swlan", "ap", "softap", "p2p", "eth", "rndis", "usb", "ncm", "bt-pan"})
            if (n.startsWith(p)) return true;
        return false;
    }

    interface Cancel { boolean cancelled(); }

    static List<Hit> scan(Context c, int port, Cancel cancel) {
        List<String> gws = gateways(c);
        List<String> hosts = candidates(c, gws);
        List<Hit> hits = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (String h : hosts) fs.add(pool.submit(() -> open(h, port, 400)));
            for (int i = 0; i < hosts.size(); i++) {
                if (cancel != null && cancel.cancelled()) break;
                try {
                    if (fs.get(i).get(10, TimeUnit.SECONDS)) hits.add(new Hit(hosts.get(i), port, gws.contains(hosts.get(i))));
                } catch (Exception ignored) { }
            }
        } finally {
            pool.shutdownNow();
        }
        return hits;
    }

    static boolean open(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
