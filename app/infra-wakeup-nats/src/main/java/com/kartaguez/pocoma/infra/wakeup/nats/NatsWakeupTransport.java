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
 */
public final class NatsWakeupTransport implements WakeupSubscriber {
    private static final Logger LOG = LoggerFactory.getLogger(NatsWakeupTransport.class);
    private static final byte[] EMPTY = new byte[0];

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
                throw new IllegalStateException("pocoma.v3.wakeup.url is required when wakeup mode is nats");
            }
            return new TransportOptions(
                    url.trim(),
                    lookup.apply("pocoma.v3.wakeup.auth-token"),
                    durationMs(lookup, "pocoma.v3.wakeup.connection-timeout-ms", DEFAULT_CONNECTION_TIMEOUT),
                    durationMs(lookup, "pocoma.v3.wakeup.reconnect-wait-ms", DEFAULT_RECONNECT_WAIT),
                    durationMs(lookup, "pocoma.v3.wakeup.ping-interval-ms", DEFAULT_PING_INTERVAL),
                    intValue(lookup, "pocoma.v3.wakeup.max-pings-out", DEFAULT_MAX_PINGS_OUT),
                    durationMs(lookup, "pocoma.v3.wakeup.connect-retry-delay-ms", DEFAULT_CONNECT_RETRY_DELAY),
                    longValue(lookup, "pocoma.v3.wakeup.reconnect-buffer-bytes", DEFAULT_RECONNECT_BUFFER_BYTES));
        }

        private static Duration durationMs(Function<String, String> lookup, String key, Duration fallback) {
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
    private final AtomicReference<Connection> connection = new AtomicReference<>();
    private final AtomicReference<WakeupState> state = new AtomicReference<>(WakeupState.DEGRADED);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Set<WakeupKey> subscribedKeys = Set.of();
    private volatile Consumer<WakeupKey> onWakeup = k -> {};
    private volatile Consumer<WakeupState> onStateChange = s -> {};
    private volatile WakeupMetrics publisherMetrics = WakeupMetrics.NOOP;
    private volatile boolean subscriberAttached;
    private Thread connectorThread;

    public NatsWakeupTransport(String url) {
        this(TransportOptions.defaults(url));
    }

    public NatsWakeupTransport(TransportOptions transportOptions) {
        this.transportOptions = Objects.requireNonNull(transportOptions, "transportOptions");
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
        Connection existing = connection.get();
        if (existing != null) {
            subscribeOn(existing);
            WakeupState current =
                    existing.getStatus() == Connection.Status.CONNECTED
                            ? WakeupState.CONNECTED
                            : WakeupState.DEGRADED;
            state.set(current);
            try {
                this.onStateChange.accept(current);
            } catch (Throwable ignored) {
            }
            return;
        }
        updateState(WakeupState.DEGRADED);
        ensureConnecting();
    }

    private void subscribeOn(Connection conn) {
        Set<WakeupKey> keys = this.subscribedKeys;
        if (keys.isEmpty()) return;
        Dispatcher dispatcher =
                conn.createDispatcher(
                        msg -> WakeupKey.parseSubject(msg.getSubject()).ifPresent(onWakeup));
        for (WakeupKey key : keys) {
            dispatcher.subscribe(key.subject());
        }
        try {
            conn.flush(transportOptions.connectionTimeout());
        } catch (Throwable ignored) {
        }
    }

    private synchronized void ensureConnecting() {
        if (closed.get() || connectorThread != null) return;
        connectorThread =
                Thread.ofPlatform()
                        .daemon(true)
                        .name("v3-wakeup-nats-connect")
                        .start(this::connectLoop);
    }

    private void connectLoop() {
        while (!closed.get() && connection.get() == null) {
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
                                .connectionListener(
                                        (conn, type) -> {
                                            if (closed.get()) return;
                                            if (connection.get() == null) return;
                                            if (type == ConnectionListener.Events.CONNECTED
                                                    || type
                                                            == ConnectionListener.Events
                                                                    .RECONNECTED) {
                                                updateState(WakeupState.CONNECTED);
                                            } else if (type
                                                            == ConnectionListener.Events
                                                                    .DISCONNECTED
                                                    || type == ConnectionListener.Events.CLOSED) {
                                                updateState(WakeupState.DEGRADED);
                                            }
                                        });
                if (transportOptions.authToken() != null) {
                    builder.token(transportOptions.authToken().toCharArray());
                }
                Connection conn = Nats.connect(builder.build());
                if (closed.get()) {
                    conn.close();
                    return;
                }
                synchronized (this) {
                    subscribeOn(conn);
                    connection.set(conn);
                }
                if (conn.getStatus() == Connection.Status.CONNECTED) {
                    updateState(WakeupState.CONNECTED);
                }
                LOG.info(
                        "Core NATS wakeup connected ({} subjects)",
                        subscribedKeys.size());
                return;
            } catch (Throwable failure) {
                updateState(WakeupState.DEGRADED);
                try {
                    Thread.sleep(transportOptions.connectRetryDelay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void updateState(WakeupState next) {
        WakeupState prev = state.getAndSet(next);
        if (prev != next || next == WakeupState.DEGRADED) {
            if (!subscriberAttached) {
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
            try {
                onStateChange.accept(next);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public WakeupState state() {
        return state.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (connectorThread != null) {
            connectorThread.interrupt();
        }
        Connection conn = connection.getAndSet(null);
        if (conn != null) {
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
