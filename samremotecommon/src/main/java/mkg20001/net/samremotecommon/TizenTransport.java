package mkg20001.net.samremotecommon;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Authorization is a TV event, not merely a successful WebSocket handshake. */
public final class TizenTransport implements RemoteTransport {
    private final Object lock = new Object();
    private final OkHttpClient client;
    private final long approvalTimeoutMillis;
    private final boolean ownsClient;
    private Attempt active;
    private long generation;
    private boolean closed;

    public TizenTransport() {
        this(createTvClient(), TimeUnit.SECONDS.toMillis(60), true);
    }

    TizenTransport(OkHttpClient client, long approvalTimeoutMillis) {
        this(client, approvalTimeoutMillis, false);
    }

    private TizenTransport(OkHttpClient client, long approvalTimeoutMillis, boolean ownsClient) {
        this.client = client;
        this.approvalTimeoutMillis = approvalTimeoutMillis;
        this.ownsClient = ownsClient;
    }

    /** Samsung TVs use self-signed certificates. This client is only for numeric TV addresses. */
    public static OkHttpClient createTvClient() {
        X509TrustManager tvTrust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{tvTrust}, null);
            return new OkHttpClient.Builder()
                    .sslSocketFactory(tls.getSocketFactory(), tvTrust)
                    .hostnameVerifier((host, session) -> isNumericAddress(host))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to initialize TV TLS", error);
        }
    }

    private static boolean isNumericAddress(String host) {
        return host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+") || host.contains(":");
    }

    @Override public TvDevice connect(TvDevice device, ClientIdentity identity, Listener listener)
            throws IOException {
        if (!isNumericAddress(device.host)) throw new IOException("TV address must be numeric");
        final long operation;
        final WebSocket previous;
        synchronized (lock) {
            if (closed) throw new IOException("TV transport is closed");
            previous = cancelActiveLocked();
            operation = generation;
        }
        if (previous != null) previous.cancel();
        try {
            return connectAttempt(device, identity, listener, operation);
        } catch (AccessDenied denied) {
            if (device.token.isEmpty()) throw denied;
            listener.onTokenInvalidated();
            // A TV can revoke a saved token. Only authorization rejection permits a fresh attempt.
            return connectAttempt(device.withToken(""), identity, listener, operation);
        }
    }

    private TvDevice connectAttempt(TvDevice device, ClientIdentity identity, Listener listener, long operation)
            throws IOException {
        Attempt attempt = new Attempt(device.token, listener);
        synchronized (lock) {
            if (generation != operation) throw new IOException("TV connection cancelled");
            active = attempt;
        }
        WebSocket socket = client.newWebSocket(new Request.Builder().url(channelUrl(device, identity)).build(),
                new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) {
                        synchronized (lock) {
                            if (active != attempt) { socket.cancel(); return; }
                            attempt.socket = socket;
                            attempt.opened.countDown();
                        }
                        if (device.token.isEmpty()) listener.onAwaitingApproval();
                    }

                    @Override public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject message = new JSONObject(text);
                            String event = message.optString("event");
                            if ("ms.channel.connect".equals(event)) {
                                synchronized (lock) {
                                    if (active != attempt || attempt.error != null) return;
                                    JSONObject data = message.optJSONObject("data");
                                    if (data != null && data.has("token")) {
                                        attempt.token = data.optString("token", "");
                                    }
                                    attempt.ready = true;
                                    attempt.done.countDown();
                                }
                            } else if (isDenied(message)) {
                                fail(attempt, new AccessDenied("TV denied remote authorization"));
                                socket.cancel();
                            } else if ("ms.channel.timeOut".equals(event)) {
                                fail(attempt, new SocketTimeoutException("TV approval timed out"));
                                socket.cancel();
                            } else if ("ms.error".equals(event)) {
                                fail(attempt, new IOException("TV reported a remote protocol error"));
                                socket.cancel();
                            }
                        } catch (JSONException error) {
                            fail(attempt, new IOException("Invalid TV response", error));
                            socket.cancel();
                        }
                    }

                    @Override public void onClosing(WebSocket socket, int code, String reason) {
                        fail(attempt, new IOException("TV closed the remote connection"));
                        socket.close(code, null);
                    }

                    @Override public void onClosed(WebSocket socket, int code, String reason) {
                        fail(attempt, new IOException("TV closed the remote connection"));
                    }

                    @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                        IOException failure = response != null && (response.code() == 401 || response.code() == 403)
                                ? new AccessDenied("TV denied remote authorization")
                                : new IOException("TV WebSocket connection failed", error);
                        fail(attempt, failure);
                    }
                });
        synchronized (lock) {
            if (active == attempt) attempt.socket = socket;
            else socket.cancel();
        }
        try {
            if (!attempt.opened.await(3, TimeUnit.SECONDS)) {
                throw new SocketTimeoutException("TV connection timed out");
            }
            if (!attempt.done.await(approvalTimeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new SocketTimeoutException("TV approval timed out");
            }
            synchronized (lock) {
                if (attempt.error != null) throw attempt.error;
                if (active != attempt || !attempt.ready) throw new IOException("TV connection cancelled");
                return device.withToken(attempt.token);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("TV connection interrupted", error);
        } finally {
            synchronized (lock) {
                if (!attempt.ready || attempt.error != null) {
                    if (active == attempt) active = null;
                    socket.cancel();
                }
            }
        }
    }

    static HttpUrl channelUrl(TvDevice device, ClientIdentity identity) {
        HttpUrl.Builder url = new HttpUrl.Builder()
                .scheme(device.port == 8002 ? "https" : "http")
                .host(device.host).port(device.port)
                .addPathSegments("api/v2/channels/samsung.remote.control")
                .addQueryParameter("name", ByteString.encodeUtf8(identity.name).base64());
        if (!device.token.isEmpty()) url.addQueryParameter("token", device.token);
        return url.build();
    }

    private static boolean isDenied(JSONObject message) {
        String event = message.optString("event").toLowerCase(Locale.ROOT);
        if (event.contains("unauthorized") || event.contains("denied")) return true;
        if (!"ms.error".equals(event)) return false;
        String data = message.optString("data").toLowerCase(Locale.ROOT);
        return data.contains("unauthorized") || data.contains("denied") || data.contains("forbidden")
                || data.contains("authentication") || data.contains("invalid token")
                || data.contains("401") || data.contains("403");
    }

    private void fail(Attempt attempt, IOException error) {
        boolean notify;
        synchronized (lock) {
            if (active != attempt || attempt.error != null) return;
            attempt.error = error;
            attempt.opened.countDown();
            attempt.done.countDown();
            notify = attempt.ready;
        }
        if (notify) attempt.listener.onDisconnected(error);
    }

    @Override public void sendKey(RemoteKey key) throws IOException {
        synchronized (lock) {
            if (active == null || !active.ready || active.error != null) {
                throw new IOException("TV is not authorized");
            }
            try {
                JSONObject params = new JSONObject().put("Cmd", "Click")
                        .put("DataOfCmd", key.wireName(TvDevice.Protocol.TIZEN))
                        .put("Option", "false").put("TypeOfRemote", "SendRemoteKey");
                String command = new JSONObject().put("method", "ms.remote.control")
                        .put("params", params).toString();
                if (!active.socket.send(command)) throw new IOException("TV connection is closed");
            } catch (JSONException error) {
                throw new IOException("Unable to encode remote command", error);
            }
        }
    }

    @Override public void close() {
        final WebSocket socket;
        synchronized (lock) {
            closed = true;
            socket = cancelActiveLocked();
        }
        if (socket != null) socket.cancel();
        if (ownsClient) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private WebSocket cancelActiveLocked() {
        generation++;
        if (active == null) return null;
        Attempt old = active;
        active = null;
        old.error = new IOException("TV connection cancelled");
        old.opened.countDown();
        old.done.countDown();
        return old.socket;
    }

    private static final class Attempt {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final Listener listener;
        WebSocket socket;
        boolean ready;
        String token;
        IOException error;

        Attempt(String token, Listener listener) {
            this.token = token;
            this.listener = listener;
        }
    }
}
