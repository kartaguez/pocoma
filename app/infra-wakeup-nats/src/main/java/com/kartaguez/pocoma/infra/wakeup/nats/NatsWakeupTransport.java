package com.kartaguez.pocoma.infra.wakeup.nats;

import com.kartaguez.pocoma.contract.consumption.wakeup.CoalescingWakeupPublisher;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupKey;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupMetrics;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupPublisher;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupState;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupSubscriber;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core NATS wakeup adapter (no JetStream, no persistence, no queue groups).
 *
 * <p>Handles broker absence at startup gracefully by entering {@link WakeupState#DEGRADED} and
 * retrying connection on a background daemon thread so PostgreSQL polling continues uninterrupted.
 *
 * <p>{@link WakeupState#CONNECTED} is announced strictly after the NATS connection is established
 * and, when a subscriber is attached, after all required subscriptions are installed on a single
 * active {@link Dispatcher} and confirmed via {@link Connection#flush(Duration)}.
 */
public final class NatsWakeupTransport implements WakeupSubscriber {
    private static final Logger LOG = LoggerFactory.getLogger(NatsWakeupTransport.class);
    private static final byte[] EMPTY = new byte[0];

    @FunctionalInterface
    interface ConnectionConnector {
        Connection connect(Options options) throws Exception;
    }

    public record TransportOptions(
            String url,
            String authToken,
            Duration connectionTimeout,
            Duration reconnectWait,
            Duration pingInterval,
            int maxPingsOut,
            Duration connectRetryDelay,
            long reconnectBufferBytes) {
        public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(2);
        public static final Duration DEFAULT_RECONNECT_WAIT = Duration.ofMillis(250);
        public static final Duration DEFAULT_PING_INTERVAL = Duration.ofSeconds(2);
        public static final int DEFAULT_MAX_PINGS_OUT = 2;
        public static final Duration DEFAULT_CONNECT_RETRY_DELAY = Duration.ofMillis(500);
        public static final long DEFAULT_RECONNECT_BUFFER_BYTES = 64L * 1024L;

        public TransportOptions {
            Objects.requireNonNull(url, "url");
            if (url.isBlank()) throw new IllegalArgumentException("NATS url must not be blank");
            authToken = (authToken == null || authToken.isBlank()) ? null : authToken;
            connectionTimeout =
                    connectionTimeout == null ? DEFAULT_CONNECTION_TIMEOUT : connectionTimeout;
            reconnectWait = reconnectWait == null ? DEFAULT_RECONNECT_WAIT : reconnectWait;
            pingInterval = pingInterval == null ? DEFAULT_PING_INTERVAL : pingInterval;
            if (maxPingsOut < 1) throw new IllegalArgumentException("maxPingsOut must be >= 1");
            connectRetryDelay =
                    connectRetryDelay == null ? DEFAULT_CONNECT_RETRY_DELAY : connectRetryDelay;
            if (reconnectBufferBytes < 0) {
                throw new IllegalArgumentException("reconnectBufferBytes must be >= 0");
            }
        }

        public static TransportOptions defaults(String url) {
            return new TransportOptions(
                    url,
                    null,
                    DEFAULT_CONNECTION_TIMEOUT,
                    DEFAULT_RECONNECT_WAIT,
                    DEFAULT_PING_INTERVAL,
                    DEFAULT_MAX_PINGS_OUT,
                    DEFAULT_CONNECT_RETRY_DELAY,
                    DEFAULT_RECONNECT_BUFFER_BYTES);
        }

        public static TransportOptions fromProperties(Function<String, String> lookup) {
            Objects.requireNonNull(lookup, "lookup");
            String url = lookup.apply("pocoma.v3.wakeup.url");
            if (url == null || url.isBlank()) {
                throw new IllegalStateException(
                        "pocoma.v3.wakeup.url is required when wakeup mode is nats");
            }
            return new TransportOptions(
                    url.trim(),
                    lookup.apply("pocoma.v3.wakeup.auth-token"),
                    durationMs(
                            lookup,
                            "pocoma.v3.wakeup.connection-timeout-ms",
                            DEFAULT_CONNECTION_TIMEOUT),
                    durationMs(
                            lookup, "pocoma.v3.wakeup.reconnect-wait-ms", DEFAULT_RECONNECT_WAIT),
                    durationMs(
                            lookup, "pocoma.v3.wakeup.ping-interval-ms", DEFAULT_PING_INTERVAL),
                    intValue(lookup, "pocoma.v3.wakeup.max-pings-out", DEFAULT_MAX_PINGS_OUT),
                    durationMs(
                            lookup,
                            "pocoma.v3.wakeup.connect-retry-delay-ms",
                            DEFAULT_CONNECT_RETRY_DELAY),
                    longValue(
                            lookup,
                            "pocoma.v3.wakeup.reconnect-buffer-bytes",
                            DEFAULT_RECONNECT_BUFFER_BYTES));
        }

        private static Duration durationMs(
                Function<String, String> lookup, String key, Duration fallback) {
            String raw = lookup.apply(key);
            if (raw == null || raw.isBlank()) return fallback;
            long ms = Long.parseLong(raw.trim());
            if (ms <= 0) throw new IllegalArgumentException(key + " must be > 0");
            return Duration.ofMillis(ms);
        }

        private static int intValue(Function<String, String> lookup, String key, int fallback) {
            String raw = lookup.apply(key);
            return (raw == null || raw.isBlank()) ? fallback : Integer.parseInt(raw.trim());
        }

        private static long longValue(Function<String, String> lookup, String key, long fallback) {
            String raw = lookup.apply(key);
            return (raw == null || raw.isBlank()) ? fallback : Long.parseLong(raw.trim());
        }
    }

    private final TransportOptions transportOptions;
    private final ConnectionConnector connector;
    private final AtomicReference<Connection> connection = new AtomicReference<>();
    private final AtomicReference<WakeupState> state = new AtomicReference<>(WakeupState.DEGRADED);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong confirmGeneration = new AtomicLong();
    private volatile Set<WakeupKey> subscribedKeys = Set.of();
    private volatile Consumer<WakeupKey> onWakeup = k -> {};
    private volatile Consumer<WakeupState> onStateChange = s -> {};
    private volatile WakeupMetrics publisherMetrics = WakeupMetrics.NOOP;
    private volatile boolean subscriberAttached;
    private volatile boolean subscriptionsConfirmed;
    private Dispatcher activeDispatcher;
    private Thread connectorThread;
    private Thread confirmThread;

    public NatsWakeupTransport(String url) {
        this(TransportOptions.defaults(url), Nats::connect);
    }

    public NatsWakeupTransport(TransportOptions transportOptions) {
        this(transportOptions, Nats::connect);
    }

    NatsWakeupTransport(TransportOptions transportOptions, ConnectionConnector connector) {
        this.transportOptions = Objects.requireNonNull(transportOptions, "transportOptions");
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    /** Creates a non-blocking coalescing publisher backed by this Core NATS connection. */
    public WakeupPublisher createPublisher(
            CoalescingWakeupPublisher.Strategy strategy, long windowMillis, WakeupMetrics metrics) {
        this.publisherMetrics = metrics == null ? WakeupMetrics.NOOP : metrics;
        this.publisherMetrics.onStateChanged(state.get());
        ensureConnecting();
        var coalescing =
                new CoalescingWakeupPublisher(
                        key -> {
                            Connection c = connection.get();
                            if (c == null || c.getStatus() != Connection.Status.CONNECTED) {
                                throw new IllegalStateException("NATS not connected");
                            }
                            c.publish(key.subject(), EMPTY);
                        },
                        strategy,
                        windowMillis,
                        metrics);
        return new WakeupPublisher() {
            @Override
            public void publish(
                    com.kartaguez.pocoma.domain.consumption.ConsumableFamily family,
                    int partition) {
                coalescing.publish(family, partition);
            }

            @Override
            public void close() {
                coalescing.close();
                NatsWakeupTransport.this.close();
            }
        };
    }

    @Override
    public synchronized void start(
            Set<WakeupKey> keys,
            Consumer<WakeupKey> onWakeup,
            Consumer<WakeupState> onStateChange) {
        this.subscribedKeys = Set.copyOf(keys);
        this.onWakeup = Objects.requireNonNull(onWakeup);
        this.onStateChange = Objects.requireNonNull(onStateChange);
        this.subscriberAttached = true;
        this.subscriptionsConfirmed = false;
        Connection existing = connection.get();
        if (existing != null) {
            if (activeDispatcher != null) {
                closeDispatcherQuietly(existing, activeDispatcher);
                activeDispatcher = null;
            }
            if (existing.getStatus() == Connection.Status.CONNECTED) {
                try {
                    Dispatcher dispatcher =
                            installAndConfirmSubscriptions(existing, this.subscribedKeys);
                    if (!closed.get() && existing.getStatus() == Connection.Status.CONNECTED) {
                        this.activeDispatcher = dispatcher;
                        this.subscriptionsConfirmed = true;
                        announceStateForce(WakeupState.CONNECTED);
                        return;
                    }
                    closeDispatcherQuietly(existing, dispatcher);
                } catch (Throwable failure) {
                    LOG.warn(
                            "Core NATS subscription confirmation failed on existing connection;"
                                    + " staying DEGRADED and retrying",
                            failure);
                }
            }
            teardownConnectionLocked(existing);
            announceStateForce(WakeupState.DEGRADED);
            ensureConnecting();
            return;
        }
        announceStateForce(WakeupState.DEGRADED);
        ensureConnecting();
    }

    private Dispatcher installAndConfirmSubscriptions(Connection conn, Set<WakeupKey> keys)
            throws Exception {
        if (conn.getStatus() != Connection.Status.CONNECTED) {
            throw new IllegalStateException("NATS connection not connected: " + conn.getStatus());
        }
        if (keys.isEmpty()) {
            return null;
        }
        Dispatcher dispatcher =
                conn.createDispatcher(
                        msg -> WakeupKey.parseSubject(msg.getSubject()).ifPresent(onWakeup));
        try {
            for (WakeupKey key : keys) {
                dispatcher.subscribe(key.subject());
            }
            conn.flush(transportOptions.connectionTimeout());
            if (closed.get()
                    || conn.getStatus() != Connection.Status.CONNECTED
                    || !dispatcher.isActive()) {
                throw new IllegalStateException(
                        "NATS subscription confirmation incomplete (status="
                                + conn.getStatus()
                                + ", active="
                                + dispatcher.isActive()
                                + ")");
            }
            return dispatcher;
        } catch (Throwable failure) {
            closeDispatcherQuietly(conn, dispatcher);
            throw failure;
        }
    }

    private synchronized void ensureConnecting() {
        if (closed.get() || connection.get() != null) return;
        if (connectorThread != null && connectorThread.isAlive()) return;
        connectorThread =
                Thread.ofPlatform()
                        .daemon(true)
                        .name("v3-wakeup-nats-connect")
                        .start(this::connectLoop);
    }

    private void connectLoop() {
        while (!closed.get() && connection.get() == null) {
            Connection conn = null;
            try {
                Options.Builder builder =
                        new Options.Builder()
                                .server(transportOptions.url())
                                .connectionTimeout(transportOptions.connectionTimeout())
                                .reconnectWait(transportOptions.reconnectWait())
                                .maxReconnects(-1)
                                .pingInterval(transportOptions.pingInterval())
                                .maxPingsOut(transportOptions.maxPingsOut())
                                .reconnectBufferSize(transportOptions.reconnectBufferBytes())
                                .connectionListener(this::handleConnectionEvent);
                if (transportOptions.authToken() != null) {
                    builder.token(transportOptions.authToken().toCharArray());
                }
                conn = connector.connect(builder.build());
                if (closed.get()) {
                    closeQuietly(conn);
                    return;
                }
                if (installAndPublishConnection(conn)) {
                    LOG.info(
                            "Core NATS wakeup connected ({} subjects confirmed)",
                            subscribedKeys.size());
                    return;
                }
                // Connection was closed during shutdown.
                return;
            } catch (Throwable failure) {
                subscriptionsConfirmed = false;
                if (conn != null && connection.get() != conn) {
                    closeQuietly(conn);
                }
                updateState(WakeupState.DEGRADED);
                if (closed.get()) return;
                try {
                    Thread.sleep(transportOptions.connectRetryDelay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean installAndPublishConnection(Connection conn) throws Exception {
        Dispatcher dispatcher = null;
        try {
            while (!closed.get()) {
                Set<WakeupKey> targetKeys;
                boolean needSub;
                synchronized (this) {
                    needSub = subscriberAttached && !subscribedKeys.isEmpty();
                    targetKeys = subscribedKeys;
                }
                if (needSub) {
                    dispatcher = installAndConfirmSubscriptions(conn, targetKeys);
                } else if (conn.getStatus() != Connection.Status.CONNECTED) {
                    throw new IllegalStateException(
                            "NATS connection not connected: " + conn.getStatus());
                }
                synchronized (this) {
                    if (closed.get()) {
                        closeDispatcherQuietly(conn, dispatcher);
                        closeQuietly(conn);
                        return false;
                    }
                    boolean nowNeedSub = subscriberAttached && !subscribedKeys.isEmpty();
                    if (nowNeedSub != needSub
                            || (nowNeedSub && !subscribedKeys.equals(targetKeys))) {
                        closeDispatcherQuietly(conn, dispatcher);
                        dispatcher = null;
                        continue;
                    }
                    this.activeDispatcher = dispatcher;
                    this.subscriptionsConfirmed = true;
                    this.connection.set(conn);
                    this.connectorThread = null;
                    break;
                }
            }
            if (closed.get()) {
                return false;
            }
            if (conn.getStatus() == Connection.Status.CONNECTED
                    && connection.get() == conn
                    && subscriptionsConfirmed) {
                updateState(WakeupState.CONNECTED);
            } else {
                subscriptionsConfirmed = false;
                updateState(WakeupState.DEGRADED);
            }
            return true;
        } catch (Throwable failure) {
            closeDispatcherQuietly(conn, dispatcher);
            throw failure;
        }
    }

    private void handleConnectionEvent(Connection conn, ConnectionListener.Events type) {
        if (closed.get()) return;
        if (connection.get() != conn) return;
        if (type == ConnectionListener.Events.DISCONNECTED
                || type == ConnectionListener.Events.CLOSED) {
            subscriptionsConfirmed = false;
            confirmGeneration.incrementAndGet();
            updateState(WakeupState.DEGRADED);
        } else if (type == ConnectionListener.Events.RECONNECTED) {
            scheduleReconnectConfirmation(conn);
        }
    }

    private void scheduleReconnectConfirmation(Connection conn) {
        long gen = confirmGeneration.incrementAndGet();
        synchronized (this) {
            if (closed.get() || connection.get() != conn) return;
            confirmThread =
                    Thread.ofPlatform()
                            .daemon(true)
                            .name("v3-wakeup-nats-confirm-" + gen)
                            .start(() -> confirmReconnect(conn, gen));
        }
    }

    private void confirmReconnect(Connection conn, long gen) {
        if (closed.get() || confirmGeneration.get() != gen || connection.get() != conn) return;
        if (conn.getStatus() != Connection.Status.CONNECTED) {
            subscriptionsConfirmed = false;
            updateState(WakeupState.DEGRADED);
            return;
        }
        boolean needSub;
        Dispatcher dispatcher;
        synchronized (this) {
            needSub = subscriberAttached && !subscribedKeys.isEmpty();
            dispatcher = activeDispatcher;
        }
        if (needSub) {
            if (dispatcher == null || !dispatcher.isActive()) {
                resetConnectionAndRetry(conn);
                return;
            }
            try {
                conn.flush(transportOptions.connectionTimeout());
            } catch (Throwable failure) {
                LOG.warn(
                        "Core NATS reconnect flush confirmation failed; resetting connection",
                        failure);
                resetConnectionAndRetry(conn);
                return;
            }
            if (closed.get() || confirmGeneration.get() != gen || connection.get() != conn) return;
            if (conn.getStatus() != Connection.Status.CONNECTED || !dispatcher.isActive()) {
                resetConnectionAndRetry(conn);
                return;
            }
        }
        if (closed.get() || confirmGeneration.get() != gen || connection.get() != conn) return;
        subscriptionsConfirmed = true;
        updateState(WakeupState.CONNECTED);
    }

    private synchronized void resetConnectionAndRetry(Connection conn) {
        if (closed.get() || connection.get() != conn) return;
        teardownConnectionLocked(conn);
        updateState(WakeupState.DEGRADED);
        ensureConnecting();
    }

    private void teardownConnectionLocked(Connection conn) {
        confirmGeneration.incrementAndGet();
        subscriptionsConfirmed = false;
        connection.compareAndSet(conn, null);
        if (activeDispatcher != null) {
            closeDispatcherQuietly(conn, activeDispatcher);
            activeDispatcher = null;
        }
        closeQuietly(conn);
    }

    private void announceStateForce(WakeupState next) {
        WakeupState prev = state.getAndSet(next);
        if (!subscriberAttached) {
            notifyPublisherMetrics(prev, next);
        }
        try {
            onStateChange.accept(next);
        } catch (Throwable ignored) {
        }
    }

    private void updateState(WakeupState next) {
        WakeupState prev = state.getAndSet(next);
        if (prev != next || next == WakeupState.DEGRADED) {
            if (!subscriberAttached) {
                notifyPublisherMetrics(prev, next);
            }
            try {
                onStateChange.accept(next);
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyPublisherMetrics(WakeupState prev, WakeupState next) {
        try {
            publisherMetrics.onStateChanged(next);
            if (next == WakeupState.DEGRADED && prev == WakeupState.CONNECTED) {
                publisherMetrics.onFallbackTransition();
            } else if (next == WakeupState.CONNECTED && prev == WakeupState.DEGRADED) {
                publisherMetrics.onReconnect();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public WakeupState state() {
        return state.get();
    }

    boolean isSubscriptionsConfirmed() {
        return subscriptionsConfirmed;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        confirmGeneration.incrementAndGet();
        Connection conn;
        Dispatcher dispatcher;
        synchronized (this) {
            if (connectorThread != null) {
                connectorThread.interrupt();
                connectorThread = null;
            }
            if (confirmThread != null) {
                confirmThread.interrupt();
                confirmThread = null;
            }
            conn = connection.getAndSet(null);
            dispatcher = activeDispatcher;
            activeDispatcher = null;
            subscriptionsConfirmed = false;
        }
        if (conn != null) {
            closeDispatcherQuietly(conn, dispatcher);
            closeQuietly(conn);
        }
    }

    private static void closeDispatcherQuietly(Connection conn, Dispatcher dispatcher) {
        if (conn == null || dispatcher == null) return;
        try {
            conn.closeDispatcher(dispatcher);
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) return;
        try {
            conn.close();
        } catch (Throwable ignored) {
        }
    }
}
