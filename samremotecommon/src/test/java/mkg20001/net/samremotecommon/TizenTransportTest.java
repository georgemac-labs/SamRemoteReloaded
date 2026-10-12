package mkg20001.net.samremotecommon;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static org.junit.Assert.*;

public class TizenTransportTest {
    private MockWebServer server;
    private TizenTransport transport;
    private ExecutorService worker;
    private final AtomicInteger approvalPrompts = new AtomicInteger();
    private final AtomicInteger invalidatedTokens = new AtomicInteger();
    private final BlockingQueue<IOException> disconnects = new LinkedBlockingQueue<>();
    private final RemoteTransport.Listener listener = new RemoteTransport.Listener() {
        @Override public void onAwaitingApproval() { approvalPrompts.incrementAndGet(); }
        @Override public void onDisconnected(IOException error) { disconnects.add(error); }
        @Override public void onTokenInvalidated() { invalidatedTokens.incrementAndGet(); }
    };
    private final ClientIdentity identity = new ClientIdentity("127.0.0.1", "test-client", "SamRemote télé 📺");

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        transport = new TizenTransport(new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS).build(), 300);
        worker = Executors.newSingleThreadExecutor();
    }

    @After public void tearDown() throws Exception {
        transport.close();
        worker.shutdownNow();
        server.close();
    }

    private TvDevice device(String token) {
        return new TvDevice("127.0.0.1", "test-tv", "Test TV", TvDevice.Protocol.TIZEN, server.getPort(), token);
    }

    private void enqueue(WebSocketListener events) {
        server.enqueue(new MockResponse.Builder().webSocketUpgrade(events).build());
    }

    private WebSocketListener reply(String message) {
        return new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { socket.send(message); }
        };
    }

    @Test public void authorizesAndSendsProtocolCommandsInOrder() throws Exception {
        BlockingQueue<String> commands = new LinkedBlockingQueue<>();
        enqueue(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                socket.send("{\"event\":\"ms.channel.connect\",\"data\":{\"token\":\"12345\"}}");
            }
            @Override public void onMessage(WebSocket socket, String message) { commands.add(message); }
        });
        assertEquals("12345", transport.connect(device(""), identity, listener).token);
        assertEquals(1, approvalPrompts.get());
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(ByteString.encodeUtf8(identity.name).base64(), request.getUrl().queryParameter("name"));
        assertNull(request.getUrl().queryParameter("token"));
        transport.sendKey(RemoteKey.VOLUP);
        transport.sendKey(RemoteKey.CHLIST);
        transport.sendKey(RemoteKey.POWEROFF);
        transport.sendKey(RemoteKey.HOME);
        transport.sendKey(RemoteKey.PLAY_PAUSE);
        transport.sendKey(RemoteKey.RETURN);
        for (String key : new String[]{"KEY_VOLUP", "KEY_CH_LIST", "KEY_POWER",
                "KEY_HOME", "KEY_PLAY_BACK", "KEY_RETURN"}) {
            String message = commands.poll(2, TimeUnit.SECONDS);
            assertNotNull(message);
            JSONObject command = new JSONObject(message);
            assertEquals("ms.remote.control", command.getString("method"));
            JSONObject params = command.getJSONObject("params");
            assertEquals(key, params.getString("DataOfCmd"));
            assertEquals("Click", params.getString("Cmd"));
            assertEquals("false", params.getString("Option"));
            assertEquals("SendRemoteKey", params.getString("TypeOfRemote"));
        }
    }

    @Test public void reusesSavedTokenWithoutPrompt() throws Exception {
        enqueue(reply("{\"event\":\"ms.channel.connect\",\"data\":{}}"));
        assertEquals("saved +/token", transport.connect(device("saved +/token"), identity, listener).token);
        assertEquals("saved +/token", server.takeRequest(2, TimeUnit.SECONDS).getUrl().queryParameter("token"));
        assertEquals(0, approvalPrompts.get());
    }

    @Test public void rejectedSavedTokenPairsOnceWithoutToken() throws Exception {
        enqueue(reply("{\"event\":\"ms.channel.unauthorized\"}"));
        enqueue(reply("{\"event\":\"ms.channel.connect\",\"data\":{\"token\":\"replacement\"}}"));
        assertEquals("replacement", transport.connect(device("revoked"), identity, listener).token);
        assertEquals("revoked", server.takeRequest(2, TimeUnit.SECONDS).getUrl().queryParameter("token"));
        assertNull(server.takeRequest(2, TimeUnit.SECONDS).getUrl().queryParameter("token"));
        assertEquals(1, approvalPrompts.get());
        assertEquals(1, invalidatedTokens.get());
    }

    @Test public void denialDoesNotLoopOrBecomeReady() throws Exception {
        enqueue(reply("{\"event\":\"ms.error\",\"data\":{\"message\":\"Unauthorized\"}}"));
        enqueue(reply("{\"event\":\"ms.channel.unauthorized\"}"));
        try {
            transport.connect(device("revoked"), identity, listener);
            fail("Expected authorization denial");
        } catch (RemoteTransport.AccessDenied expected) { }
        assertEquals(2, server.getRequestCount());
        try { transport.sendKey(RemoteKey.ENTER); fail("Not ready"); }
        catch (IOException expected) { }
    }

    @Test public void handshakeAndUnrelatedEventsDoNotAuthorize() throws Exception {
        enqueue(reply("{\"event\":\"ed.edenTV.update\"}"));
        try {
            transport.connect(device(""), identity, listener);
            fail("Expected approval timeout");
        } catch (SocketTimeoutException expected) { }
        assertNull(disconnects.poll());
    }

    @Test public void disconnectAfterAuthorizationNotifiesOnceAndBlocksKeys() throws Exception {
        BlockingQueue<WebSocket> sockets = new LinkedBlockingQueue<>();
        enqueue(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                sockets.add(socket);
                socket.send("{\"event\":\"ms.channel.connect\",\"data\":{}}");
            }
        });
        transport.connect(device("saved"), identity, listener);
        sockets.take().close(1000, "bye");
        assertNotNull(disconnects.poll(2, TimeUnit.SECONDS));
        assertNull(disconnects.poll(100, TimeUnit.MILLISECONDS));
        try { transport.sendKey(RemoteKey.ENTER); fail("Disconnected"); }
        catch (IOException expected) { }
    }

    @Test public void closeCancelsPendingApprovalAndIgnoresOldEvents() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        enqueue(new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { opened.countDown(); }
        });
        Future<IOException> connection = worker.submit(() -> {
            try { transport.connect(device(""), identity, listener); return null; }
            catch (IOException error) { return error; }
        });
        assertTrue(opened.await(2, TimeUnit.SECONDS));
        transport.close();
        assertNotNull(connection.get(2, TimeUnit.SECONDS));
        transport = new TizenTransport(new OkHttpClient(), 300);
        enqueue(reply("{\"event\":\"ms.channel.connect\",\"data\":{\"token\":\"new\"}}"));
        assertEquals("new", transport.connect(device(""), identity, listener).token);
        assertNull(disconnects.poll());
    }

    @Test public void closingBeforeQueuedConnectPreventsNetworkRequest() throws Exception {
        transport.close();
        try { transport.connect(device(""), identity, listener); fail("Closed transport must not connect"); }
        catch (IOException expected) { }
        assertEquals(0, server.getRequestCount());
    }

    @Test public void httpFailureWakesConnectionWithoutApprovalDelay() throws Exception {
        server.enqueue(new MockResponse.Builder().code(503).build());
        try { transport.connect(device(""), identity, listener); fail("Expected HTTP failure"); }
        catch (IOException expected) { assertFalse(expected instanceof RemoteTransport.AccessDenied); }
        assertEquals(0, approvalPrompts.get());
    }

    @Test public void selectsSecureEndpointAndKeepsTlsExceptionScopedToNumericAddresses() {
        TvDevice secure = new TvDevice("192.168.0.13", "tv", "TV", TvDevice.Protocol.TIZEN, 8002, "");
        TvDevice plain = new TvDevice("192.168.0.13", "tv", "TV", TvDevice.Protocol.TIZEN, 8001, "");
        assertEquals("https", TizenTransport.channelUrl(secure, identity).scheme());
        assertEquals("http", TizenTransport.channelUrl(plain, identity).scheme());
        assertTrue(TizenTransport.createTvClient().hostnameVerifier().verify("192.168.0.13", null));
        assertFalse(TizenTransport.createTvClient().hostnameVerifier().verify("example.com", null));
        assertFalse(TizenTransport.createTvClient().followRedirects());
    }
}
