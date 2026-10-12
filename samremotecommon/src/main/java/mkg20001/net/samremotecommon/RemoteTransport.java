package mkg20001.net.samremotecommon;

import java.io.IOException;

/** Wire protocols own authorization; the controller owns user intent and command ordering. */
public interface RemoteTransport {
    interface Listener {
        void onAwaitingApproval();
        void onDisconnected(IOException error);
        default void onTokenInvalidated() { }
    }

    TvDevice connect(TvDevice device, ClientIdentity identity, Listener listener) throws IOException;
    void sendKey(RemoteKey key) throws IOException;
    void close();

    final class AccessDenied extends IOException {
        public AccessDenied(String message) { super(message); }
    }
}
