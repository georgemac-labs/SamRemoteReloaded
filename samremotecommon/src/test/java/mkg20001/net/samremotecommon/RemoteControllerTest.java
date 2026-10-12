package mkg20001.net.samremotecommon;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RemoteControllerTest {
    private static TvDevice device(String id) {
        return new TvDevice("192.168.0.13", id, id, TvDevice.Protocol.TIZEN, 8002, "");
    }

    private static final class Store implements RemoteController.Store {
        volatile TvDevice saved;
        public TvDevice load() { return saved; }
        public void save(TvDevice value) { saved = value; }
    }

    private static final class Transport implements RemoteTransport {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch approval;
        final CountDownLatch sending = new CountDownLatch(1);
        final BlockingQueue<RemoteKey> keys = new LinkedBlockingQueue<>();
        final AtomicInteger sends = new AtomicInteger();
        volatile Listener listener;
        volatile boolean closed;
        volatile IOException connectError, sendError;
        volatile CountDownLatch sendGate;

        Transport(boolean waitForApproval) { approval = new CountDownLatch(waitForApproval ? 1 : 0); }
        public TvDevice connect(TvDevice device, ClientIdentity identity, Listener listener) throws IOException {
            this.listener = listener;
            listener.onAwaitingApproval();
            entered.countDown();
            await(approval);
            if (connectError != null) throw connectError;
            // A late successful callback is deliberate: the controller must reject cancelled attempts.
            return device.withToken("paired-" + device.id);
        }
        public void sendKey(RemoteKey key) throws IOException {
            sends.incrementAndGet();
            sending.countDown();
            if (sendGate != null) await(sendGate);
            if (sendError != null) throw sendError;
            keys.add(key);
        }
        public void close() { closed = true; approval.countDown(); }
        private static void await(CountDownLatch latch) throws IOException {
            try {
                if (!latch.await(2, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Store store = new Store();
        final BlockingQueue<RemoteController.State> states = new LinkedBlockingQueue<>();
        final RemoteController controller;
        Fixture(Transport... transports) {
            BlockingQueue<Transport> choices = new LinkedBlockingQueue<>(Arrays.asList(transports));
            controller = new RemoteController(store, device -> choices.remove(),
                    new ClientIdentity("ip", "id", "SamRemote"), Runnable::run);
            controller.addObserver((state, device) -> states.add(state));
        }
        void state(RemoteController.State expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                RemoteController.State next = states.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (next == expected) return;
            }
            fail("Expected " + expected + ", actual " + controller.getState());
        }
        public void close() { controller.shutdown(); }
    }

    @Test public void discardsPreReadyKeysThenPreservesCommandOrderAndToken() throws Exception {
        Transport transport = new Transport(true);
        try (Fixture f = new Fixture(transport)) {
            f.controller.sendKey(RemoteKey.POWEROFF);
            f.controller.connect(device("tv"));
            assertTrue(transport.entered.await(1, TimeUnit.SECONDS));
            f.controller.sendKey(RemoteKey.POWEROFF);
            transport.approval.countDown();
            f.state(RemoteController.State.READY);
            assertEquals("paired-tv", f.store.saved.token);
            f.controller.sendKey(RemoteKey.UP);
            f.controller.sendKey(RemoteKey.VOLUP);
            f.controller.sendKey(RemoteKey.RETURN);
            assertEquals(RemoteKey.UP, transport.keys.poll(1, TimeUnit.SECONDS));
            assertEquals(RemoteKey.VOLUP, transport.keys.poll(1, TimeUnit.SECONDS));
            assertEquals(RemoteKey.RETURN, transport.keys.poll(1, TimeUnit.SECONDS));
            assertEquals(3, transport.sends.get());
        }
    }

    @Test public void failedCommandIsNotReplayedAndQueuedKeysAreDiscarded() throws Exception {
        Transport first = new Transport(false);
        Transport second = new Transport(false);
        first.sendError = new IOException("Lost response after command");
        first.sendGate = new CountDownLatch(1);
        try (Fixture f = new Fixture(first, second)) {
            f.controller.connect(device("tv"));
            f.state(RemoteController.State.READY);
            f.controller.sendKey(RemoteKey.VOLUP);
            assertTrue(first.sending.await(1, TimeUnit.SECONDS));
            f.controller.sendKey(RemoteKey.VOLUP);
            first.sendGate.countDown();
            f.state(RemoteController.State.ERROR);
            assertTrue(first.closed);
            f.controller.connect(device("tv"));
            f.state(RemoteController.State.READY);
            f.controller.sendKey(RemoteKey.RETURN);
            assertEquals(RemoteKey.RETURN, second.keys.poll(1, TimeUnit.SECONDS));
            assertEquals(1, first.sends.get());
            assertEquals(1, second.sends.get());
        }
    }

    @Test public void replacementClosesAuthorizationAndIgnoresStaleCallbacks() throws Exception {
        Transport first = new Transport(true);
        Transport second = new Transport(false);
        try (Fixture f = new Fixture(first, second)) {
            f.controller.connect(device("old"));
            assertTrue(first.entered.await(1, TimeUnit.SECONDS));
            f.controller.connect(device("new"));
            f.state(RemoteController.State.READY);
            assertTrue(first.closed);
            assertEquals("new", f.controller.getDevice().id);
            first.listener.onAwaitingApproval();
            first.listener.onDisconnected(new IOException("Late old failure"));
            assertEquals(RemoteController.State.READY, f.controller.getState());
            assertEquals("paired-new", f.store.saved.token);
            f.controller.disconnect();
            assertTrue(second.closed);
            second.listener.onDisconnected(new IOException("Late close"));
            assertEquals(RemoteController.State.DISCONNECTED, f.controller.getState());
        }
    }

    @Test public void distinguishesDenialAndTimeout() throws Exception {
        for (IOException error : new IOException[]{new RemoteTransport.AccessDenied("Denied"),
                new SocketTimeoutException("Timed out")}) {
            Transport transport = new Transport(false);
            transport.connectError = error;
            try (Fixture f = new Fixture(transport)) {
                f.controller.connect(device("tv"));
                f.state(error instanceof RemoteTransport.AccessDenied ? RemoteController.State.DENIED
                        : RemoteController.State.TIMED_OUT);
                assertTrue(transport.closed);
            }
        }
    }
}
