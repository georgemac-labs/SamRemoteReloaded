package mkg20001.net.samremotecommon;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LegacyTransportTest {
    private static final ClientIdentity IDENTITY = new ClientIdentity("ip", "id", "TV");

    private static final class Listener implements RemoteTransport.Listener {
        final AtomicInteger approvals = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        public void onAwaitingApproval() { approvals.incrementAndGet(); }
        public void onDisconnected(IOException error) { failures.incrementAndGet(); }
    }

    private interface TvSession { void run(Socket socket) throws Exception; }

    private static final class FakeTv implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> session;
        FakeTv(TvSession behavior) throws IOException {
            session = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    behavior.run(socket);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
        }
        TvDevice device() {
            return new TvDevice("127.0.0.1", "tv", "TV", TvDevice.Protocol.LEGACY,
                    server.getLocalPort(), "");
        }
        public void close() throws Exception {
            server.close();
            executor.shutdownNow();
            session.get(3, TimeUnit.SECONDS);
        }
    }

    private static byte[] readPacket(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        assertEquals(0, input.readUnsignedByte());
        int nameLength = input.readUnsignedByte() | (input.readUnsignedByte() << 8);
        byte[] name = new byte[nameLength];
        input.readFully(name);
        int length = input.readUnsignedByte() | (input.readUnsignedByte() << 8);
        byte[] payload = new byte[length];
        input.readFully(payload);
        return payload;
    }

    private static void response(Socket socket, byte... payload) throws IOException {
        // Deliberately deliver individual bytes to exercise fragmented frame reads.
        byte[] header = {0, 2, 0, 'T', 'V', (byte) payload.length, 0};
        for (byte value : header) socket.getOutputStream().write(value);
        for (byte value : payload) socket.getOutputStream().write(value);
        socket.getOutputStream().flush();
    }

    @Test public void sendsBinaryHandshakeAndKeyOnAuthorizedSession() throws Exception {
        Listener listener = new Listener();
        try (FakeTv tv = new FakeTv(socket -> {
            assertArrayEquals(new byte[]{0x64, 0, 4, 0, 'a', 'X', 'A', '=',
                    4, 0, 'a', 'W', 'Q', '=', 4, 0, 'V', 'F', 'Y', '='}, readPacket(socket));
            response(socket, (byte) 0x0a, (byte) 0);
            response(socket, (byte) 0x0a, (byte) 0);
            response(socket, (byte) 0x64, (byte) 0, (byte) 1, (byte) 0);
            byte[] expected = "\0\0\0\14\0S0VZX1ZPTFVQ".getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(expected, readPacket(socket));
            response(socket, (byte) 0, (byte) 0, (byte) 0, (byte) 0);
            for (String command : new String[]{"\0\0\0\20\0S0VZX0NPTlRFTlRT",
                    "\0\0\0\14\0S0VZX1BMQVk=", "\0\0\0\14\0S0VZX1BBVVNF"}) {
                assertArrayEquals(command.getBytes(StandardCharsets.US_ASCII), readPacket(socket));
                response(socket, (byte) 0, (byte) 0, (byte) 0, (byte) 0);
            }
        })) {
            LegacyTransport transport = new LegacyTransport();
            transport.connect(tv.device(), IDENTITY, listener);
            transport.sendKey(RemoteKey.VOLUP);
            transport.sendKey(RemoteKey.HOME);
            transport.sendKey(RemoteKey.PLAY);
            transport.sendKey(RemoteKey.PAUSE);
            transport.close();
            assertEquals(1, listener.approvals.get());
            assertEquals(0, listener.failures.get());
        }
    }

    @Test(expected = UnsupportedOperationException.class)
    public void legacyHasNoAssumedPlaybackToggle() {
        RemoteKey.PLAY_PAUSE.wireName(TvDevice.Protocol.LEGACY);
    }

    @Test public void deniedAndCancelledAuthorizationNeverBecomeConnected() throws Exception {
        for (byte[] reply : new byte[][]{{0x64, 0, 0, 0}, {0x65, 0}}) {
            try (FakeTv tv = new FakeTv(socket -> {
                readPacket(socket);
                response(socket, reply);
                assertEquals(-1, socket.getInputStream().read());
            })) {
                LegacyTransport transport = new LegacyTransport();
                try {
                    transport.connect(tv.device(), IDENTITY, new Listener());
                    fail("Authorization should fail");
                } catch (RemoteTransport.AccessDenied expected) { }
                try {
                    transport.sendKey(RemoteKey.UP);
                    fail("Denied transport should be closed");
                } catch (IOException expected) { }
            }
        }
    }

    @Test public void stalledAuthorizationHasDeadline() throws Exception {
        try (FakeTv tv = new FakeTv(socket -> {
            readPacket(socket);
            assertEquals(-1, socket.getInputStream().read());
        })) {
            LegacyTransport transport = new LegacyTransport(1000, 100, 100);
            try {
                transport.connect(tv.device(), IDENTITY, new Listener());
                fail("Authorization should time out");
            } catch (SocketTimeoutException expected) { }
        }
    }

    @Test public void stalledCommandClosesSessionAndReportsDisconnect() throws Exception {
        Listener listener = new Listener();
        try (FakeTv tv = new FakeTv(socket -> {
            readPacket(socket);
            response(socket, (byte) 0x64, (byte) 0, (byte) 1, (byte) 0);
            readPacket(socket);
            assertEquals(-1, socket.getInputStream().read());
        })) {
            LegacyTransport transport = new LegacyTransport(1000, 1000, 100);
            transport.connect(tv.device(), IDENTITY, listener);
            try {
                transport.sendKey(RemoteKey.UP);
                fail("Command should time out");
            } catch (SocketTimeoutException expected) { }
            assertEquals(1, listener.failures.get());
        }
    }

    @Test public void truncatedResponseFailsRatherThanGrantingAuthorization() throws Exception {
        try (FakeTv tv = new FakeTv(socket -> {
            readPacket(socket);
            socket.getOutputStream().write(new byte[]{0, 2, 0, 'T'});
        })) {
            LegacyTransport transport = new LegacyTransport();
            try {
                transport.connect(tv.device(), IDENTITY, new Listener());
                fail("Truncated response should fail");
            } catch (IOException expected) {
                assertEquals("TV closed the connection", expected.getMessage());
            }
        }
    }

    @Test public void closeCancelsBlockedAuthorization() throws Exception {
        java.util.concurrent.CountDownLatch handshake = new java.util.concurrent.CountDownLatch(1);
        try (FakeTv tv = new FakeTv(socket -> {
            readPacket(socket);
            handshake.countDown();
            assertEquals(-1, socket.getInputStream().read());
        })) {
            LegacyTransport transport = new LegacyTransport();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> connection = executor.submit(() -> {
                    try {
                        transport.connect(tv.device(), IDENTITY, new Listener());
                        fail("Cancelled connection should fail");
                    } catch (IOException expected) { }
                });
                assertTrue(handshake.await(1, TimeUnit.SECONDS));
                transport.close();
                connection.get(1, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }
}
