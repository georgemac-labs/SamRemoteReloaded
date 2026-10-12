package mkg20001.net.samremotecommon;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One ordered command stream is shared by the remote, shortcuts and tile. */
public final class RemoteController {
    public enum State { DISCONNECTED, CONNECTING, AWAITING_APPROVAL, READY, DENIED, TIMED_OUT, ERROR }
    public interface Observer { void onState(State state, TvDevice device); }
    public interface Store { TvDevice load(); void save(TvDevice device); }
    public interface TransportFactory { RemoteTransport create(TvDevice device); }

    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private final Executor callbacks;
    private final Store store;
    private final TransportFactory factory;
    private ClientIdentity identity;
    private volatile State state = State.DISCONNECTED;
    private volatile TvDevice device;
    private RemoteTransport transport;
    private long generation;

    public RemoteController(Store store, TransportFactory factory, ClientIdentity identity, Executor callbacks) {
        this.store = store;
        this.factory = factory;
        this.identity = identity;
        this.callbacks = callbacks;
        device = store.load();
    }

    public State getState() { return state; }
    public TvDevice getDevice() { return device; }

    public void addObserver(Observer observer) {
        observers.add(observer);
        callbacks.execute(() -> { if (observers.contains(observer)) observer.onState(state, device); });
    }

    public void removeObserver(Observer observer) { observers.remove(observer); }

    public synchronized void setClientIdentity(ClientIdentity identity) { this.identity = identity; }

    public synchronized void connect(TvDevice selected) {
        if (selected == null) return;
        // Closing the previous transport interrupts authorization before the executor starts a new attempt.
        disconnect();
        device = selected;
        store.save(selected);
        final long attempt = ++generation;
        final RemoteTransport next = factory.create(selected);
        final ClientIdentity connectionIdentity = identity;
        transport = next;
        publish(State.CONNECTING);
        commands.execute(() -> {
            try {
                TvDevice connected = next.connect(selected, connectionIdentity, new RemoteTransport.Listener() {
                    public void onAwaitingApproval() { update(attempt, State.AWAITING_APPROVAL); }
                    public void onDisconnected(IOException error) { failed(attempt, error); }
                    public void onTokenInvalidated() {
                        synchronized (RemoteController.this) {
                            if (generation != attempt) return;
                            device = device.withToken("");
                            store.save(device);
                        }
                    }
                });
                synchronized (RemoteController.this) {
                    if (generation != attempt || state == State.ERROR || state == State.DENIED
                            || state == State.TIMED_OUT || state == State.DISCONNECTED) {
                        next.close();
                        return;
                    }
                    device = connected;
                    store.save(connected);
                    publish(State.READY);
                }
            } catch (IOException | RuntimeException error) {
                failed(attempt, error);
            }
        });
    }

    public synchronized void sendKey(RemoteKey key) {
        if (state != State.READY || transport == null) return;
        final long attempt = generation;
        final RemoteTransport current = transport;
        commands.execute(() -> {
            synchronized (RemoteController.this) {
                if (generation != attempt || state != State.READY) return;
            }
            try {
                current.sendKey(key);
            } catch (IOException | RuntimeException error) {
                // A failed command may already have reached the TV. Reconnection must never replay it.
                failed(attempt, error);
            }
        });
    }

    public synchronized void disconnect() {
        ++generation;
        if (transport != null) transport.close();
        transport = null;
        publish(State.DISCONNECTED);
    }

    public void shutdown() {
        disconnect();
        commands.shutdownNow();
    }

    private synchronized void failed(long attempt, Exception error) {
        if (generation != attempt) return;
        if (transport != null) transport.close();
        transport = null;
        ++generation;
        publish(error instanceof RemoteTransport.AccessDenied ? State.DENIED
                : error instanceof SocketTimeoutException ? State.TIMED_OUT : State.ERROR);
    }

    private synchronized void update(long attempt, State value) {
        if (generation == attempt) publish(value);
    }

    private void publish(State value) {
        state = value;
        final long version = generation;
        final TvDevice snapshot = device;
        for (Observer observer : observers) {
            callbacks.execute(() -> {
                synchronized (RemoteController.this) {
                    if (generation != version || state != value || !observers.contains(observer)) return;
                }
                observer.onState(value, snapshot);
            });
        }
    }
}
