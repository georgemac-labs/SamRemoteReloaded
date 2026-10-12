package mkg20001.net.samremotecommon;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import okio.ByteString;

/** Keeps the legacy authorization and command responses on one ordered TCP session. */
public final class LegacyTransport implements RemoteTransport {
    private final Object socketLock = new Object();
    private final int connectTimeout, approvalTimeout, commandTimeout;
    private Socket socket;
    private boolean closed;
    private Listener listener;

    public LegacyTransport() { this(3000, 60000, 5000); }

    LegacyTransport(int connectTimeout, int approvalTimeout, int commandTimeout) {
        this.connectTimeout = connectTimeout;
        this.approvalTimeout = approvalTimeout;
        this.commandTimeout = commandTimeout;
    }

    @Override
    public synchronized TvDevice connect(TvDevice device, ClientIdentity identity,
                                         Listener listener) throws IOException {
        Socket client = new Socket();
        synchronized (socketLock) {
            if (closed) throw new IOException("Connection cancelled");
            if (socket != null) throw new IOException("Already connected");
            socket = client;
            this.listener = listener;
        }
        try {
            client.connect(new InetSocketAddress(device.host, device.port), connectTimeout);
            client.setTcpNoDelay(true);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(0x64);
            payload.write(0);
            writeString(payload, identity.address);
            writeString(payload, identity.id);
            writeString(payload, identity.name);
            writePacket(client, payload.toByteArray());
            readResponse(client, approvalTimeout, true);
            return device;
        } catch (IOException error) {
            close();
            throw error;
        }
    }

    @Override
    public synchronized void sendKey(RemoteKey key) throws IOException {
        Socket client;
        synchronized (socketLock) {
            if (closed || socket == null) throw new IOException("Not connected");
            client = socket;
        }
        try {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(new byte[]{0, 0, 0});
            writeString(payload, key.wireName(TvDevice.Protocol.LEGACY));
            writePacket(client, payload.toByteArray());
            readResponse(client, commandTimeout, false);
        } catch (IOException error) {
            close();
            listener.onDisconnected(error);
            throw error;
        }
    }

    private void readResponse(Socket client, int timeout, boolean authorization) throws IOException {
        DataInputStream input = new DataInputStream(client.getInputStream());
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        boolean approvalReported = false;
        while (true) {
            byte[] header = readFully(client, input, 3, deadline);
            readFully(client, input, unsignedLength(header, 1), deadline);
            byte[] length = readFully(client, input, 2, deadline);
            byte[] response = readFully(client, input, unsignedLength(length, 0), deadline);
            if (Arrays.equals(response, new byte[]{0x64, 0, 1, 0})) return;
            if (!authorization && Arrays.equals(response, new byte[]{0, 0, 0, 0})) return;
            if (Arrays.equals(response, new byte[]{0x64, 0, 0, 0})
                    || (response.length > 0 && response[0] == 0x65)) {
                throw new AccessDenied("TV denied remote authorization");
            }
            if (authorization && response.length > 0 && response[0] == 0x0a) {
                if (!approvalReported) {
                    listener.onAwaitingApproval();
                    approvalReported = true;
                }
                continue;
            }
            throw new IOException("Unexpected legacy TV response");
        }
    }

    // The deadline covers the whole frame, so partial data cannot prolong a blocked operation.
    private static byte[] readFully(Socket client, DataInputStream input, int length,
                                    long deadline) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("TV response timed out");
            client.setSoTimeout((int) Math.max(1, (remaining + 999_999) / 1_000_000));
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new IOException("TV closed the connection");
            offset += count;
        }
        return result;
    }

    private static int unsignedLength(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = ByteString.of(value.getBytes(StandardCharsets.UTF_8))
                .base64().getBytes(StandardCharsets.US_ASCII);
        writeLength(out, bytes.length);
        out.write(bytes);
    }

    private static void writeLength(ByteArrayOutputStream out, int length) throws IOException {
        if (length > 65535) throw new IOException("Legacy packet too large");
        out.write(length & 0xff);
        out.write((length >> 8) & 0xff);
    }

    private static void writePacket(Socket client, byte[] payload) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        // An empty application identifier avoids tying the remote to a particular TV model.
        packet.write(new byte[]{0, 0, 0});
        writeLength(packet, payload.length);
        packet.write(payload);
        client.getOutputStream().write(packet.toByteArray());
        client.getOutputStream().flush();
    }

    @Override
    public void close() {
        synchronized (socketLock) {
            closed = true;
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
                socket = null;
            }
        }
    }
}
