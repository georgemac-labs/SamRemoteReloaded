package mkg20001.net.samremotecommon;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Discovery never authorizes clients or sends keys; pairing belongs to the selected transport. */
public final class DeviceDiscovery implements AutoCloseable {
    public static final class Subnet {
        public final String address;
        public final int prefix;
        public Subnet(String address, int prefix) {
            ipv4(address);
            if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("Invalid prefix");
            this.address = address;
            this.prefix = prefix;
        }
        public boolean contains(String host) {
            try { return (ipv4(host) & mask(prefix)) == (ipv4(address) & mask(prefix)); }
            catch (IllegalArgumentException e) { return false; }
        }
        public List<String> scanHosts() {
            List<String> hosts = new ArrayList<>();
            long count = 1L << (32 - prefix);
            if (count > 256) return hosts;
            long first = ipv4(address) & mask(prefix);
            for (long offset = prefix < 31 ? 1 : 0; offset < count - (prefix < 31 ? 1 : 0); offset++) {
                String host = format(first + offset);
                if (!host.equals(address)) hosts.add(host);
            }
            return hosts;
        }
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ExecutorService probes = Executors.newFixedThreadPool(16);
    private final Set<Call> calls = Collections.newSetFromMap(new ConcurrentHashMap<Call, Boolean>());
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile DatagramSocket ssdp;
    private final OkHttpClient http = TizenTransport.createTvClient().newBuilder()
            .connectTimeout(400, TimeUnit.MILLISECONDS).readTimeout(400, TimeUnit.MILLISECONDS)
            .callTimeout(400, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build();

    public List<TvDevice> search(Subnet subnet, TvDevice remembered) throws IOException {
        try { return searchInternal(subnet, remembered); }
        finally { close(); }
    }

    private List<TvDevice> searchInternal(Subnet subnet, TvDevice remembered) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        LinkedHashMap<String, TvDevice> found = new LinkedHashMap<>();
        // Remembered candidates must still identify themselves before a token can be reused.
        if (remembered != null && subnet.contains(remembered.host)) {
            TvDevice device = probe(remembered.host);
            if (device != null && device.id.equals(remembered.id)) {
                found.put(device.id, device.withToken(remembered.token));
                return new ArrayList<>(found.values());
            }
            if (device != null) found.put(device.id, device);
        }
        Set<String> hosts = new LinkedHashSet<>(ssdpHosts(subnet));
        hosts.addAll(subnet.scanHosts());
        CompletionService<TvDevice> completed = new ExecutorCompletionService<>(probes);
        int pending = 0;
        for (String host : hosts) {
            if (cancelled.get()) break;
            completed.submit(() -> probe(host));
            pending++;
        }
        try {
            while (pending-- > 0 && !cancelled.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<TvDevice> result = completed.poll(remaining, TimeUnit.NANOSECONDS);
                if (result == null) break;
                try {
                    TvDevice device = result.get();
                    if (device != null) {
                        if (remembered != null && device.id.equals(remembered.id)) device = device.withToken(remembered.token);
                        found.put(device.id, device);
                    }
                } catch (java.util.concurrent.ExecutionException error) {
                    if (error.getCause() instanceof SecurityException) throw (SecurityException) error.getCause();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Discovery cancelled", e);
        }
        return new ArrayList<>(found.values());
    }

    public TvDevice probe(String host) {
        ipv4(host);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        TvDevice modern = apiDevice(host, 8001);
        if (modern != null) {
            // Probe secure availability without pairing. Use 8001 only when the TLS API is absent.
            TvDevice secure = apiDevice(host, 8002);
            return secure == null ? modern : secure;
        }
        modern = apiDevice(host, 8002);
        if (modern != null || cancelled.get()) return modern;
        int remaining = (int) TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) return null;
        Socket socket = new Socket();
        sockets.add(socket);
        try (Socket ignored = socket) {
            if (cancelled.get()) return null;
            socket.connect(new InetSocketAddress(host, 55000), Math.min(400, remaining));
            return new TvDevice(host, host, host, TvDevice.Protocol.LEGACY, 55000, "");
        } catch (IOException e) { return null; }
        finally { sockets.remove(socket); }
    }

    private TvDevice apiDevice(String host, int port) {
        if (cancelled.get()) return null;
        String url = (port == 8002 ? "https://" : "http://") + host + ":" + port + "/api/v2/";
        Call call = http.newCall(new Request.Builder().url(url).build());
        calls.add(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONObject body = new JSONObject(response.body().string());
            JSONObject device = body.optJSONObject("device");
            if (device == null || !"Samsung SmartTV".equals(device.optString("type"))) return null;
            return new TvDevice(host, device.optString("id", host), device.optString("name", host),
                    TvDevice.Protocol.TIZEN, port, "");
        } catch (IOException | org.json.JSONException e) { return null; }
        finally { calls.remove(call); }
    }

    private Set<String> ssdpHosts(Subnet subnet) throws IOException {
        Set<String> hosts = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(subnet.address, 0))) {
            ssdp = socket;
            socket.setSoTimeout(200);
            byte[] query = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(query, query.length, InetAddress.getByName("239.255.255.250"), 1900));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!cancelled.get() && System.nanoTime() < deadline) {
                DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
                try { socket.receive(packet); }
                catch (java.net.SocketTimeoutException e) { continue; }
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
                for (String line : message.split("\r?\n")) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("location:")) {
                        try {
                            String host = URI.create(line.substring(9).trim()).getHost();
                            if (subnet.contains(host) && !host.equals(subnet.address)) hosts.add(host);
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
        } catch (IOException e) {
            // Direct probes still work on networks that do not support multicast.
            if (cancelled.get()) throw e;
        } finally { ssdp = null; }
        return hosts;
    }

    public void close() {
        cancelled.set(true);
        if (ssdp != null) ssdp.close();
        for (Call call : calls) call.cancel();
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        probes.shutdownNow();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }

    public static boolean isIpv4(String address) {
        try { ipv4(address); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private static long ipv4(String address) {
        if (address == null) throw new IllegalArgumentException("Missing IPv4 address");
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
        long value = 0;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid IPv4 address");
            int octet = Integer.parseInt(part);
            if (octet > 255) throw new IllegalArgumentException("Invalid IPv4 address");
            value = (value << 8) | octet;
        }
        return value;
    }

    private static long mask(int prefix) { return (0xffffffffL << (32 - prefix)) & 0xffffffffL; }
    private static String format(long value) {
        return ((value >>> 24) & 255) + "." + ((value >>> 16) & 255) + "."
                + ((value >>> 8) & 255) + "." + (value & 255);
    }
}
