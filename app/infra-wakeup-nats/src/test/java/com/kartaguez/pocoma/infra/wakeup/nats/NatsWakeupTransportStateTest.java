package com.kartaguez.pocoma.infra.wakeup.nats;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.wakeup.CoalescingWakeupPublisher;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupKey;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupMetrics;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupState;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.MessageHandler;
import io.nats.client.Options;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Deterministic state-machine tests for {@link NatsWakeupTransport}:
 * flush failure, unconfirmed subscriptions, disconnect during subscribe,
 * reconnect confirmation, no premature CONNECTED, and single active dispatcher.
 */
final class NatsWakeupTransportStateTest {

    private static NatsWakeupTransport.TransportOptions fastOpts() {
        return new NatsWakeupTransport.TransportOptions(
                "nats://127.0.0.1:4222",
                null,
                Duration.ofMillis(200),
                Duration.ofMillis(50),
                Duration.ofMillis(500),
                2,
                Duration.ofMillis(30),
                8192);
    }

    private static final class FakeNatsConnection {
        final AtomicReference<Connection.Status> status =
                new AtomicReference<>(Connection.Status.CONNECTED);
        final AtomicInteger dispatchersCreated = new AtomicInteger();
        final AtomicInteger dispatchersClosed = new AtomicInteger();
        final AtomicInteger connectionClosed = new AtomicInteger();
        final AtomicInteger flushCalls = new AtomicInteger();
        final List<String> subscribedSubjects = new CopyOnWriteArrayList<>();
        final AtomicBoolean dispatcherActive = new AtomicBoolean(true);
        final AtomicReference<Runnable> onFlush = new AtomicReference<>(() -> {});
        final AtomicReference<ThrowingRunnable> flushAction = new AtomicReference<>(() -> {});
        volatile ConnectionListener listener;
        volatile MessageHandler handler;
        final Connection proxy;

        @FunctionalInterface
        interface ThrowingRunnable {
            void run() throws Exception;
        }

        FakeNatsConnection(Options options) {
            this.listener = options.getConnectionListener();
            this.proxy =
                    (Connection)
                            Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (p, method, args) -> {
                                        switch (method.getName()) {
                                            case "getStatus" -> {
                                                return status.get();
                                            }
                                            case "createDispatcher" -> {
                                                dispatchersCreated.incrementAndGet();
                                                if (args != null && args.length == 1) {
                                                    handler = (MessageHandler) args[0];
                                                }
                                                return createDispatcherProxy();
                                            }
                                            case "closeDispatcher" -> {
                                                dispatchersClosed.incrementAndGet();
                                                dispatcherActive.set(false);
                                                return null;
                                            }
                                            case "flush" -> {
                                                flushCalls.incrementAndGet();
                                                onFlush.get().run();
                                                flushAction.get().run();
                                                return null;
                                            }
                                            case "close" -> {
                                                connectionClosed.incrementAndGet();
                                                status.set(Connection.Status.CLOSED);
                                                return null;
                                            }
                                            case "publish" -> {
                                                return null;
                                            }
                                            default -> {
                                                return null;
                                            }
                                        }
                                    });
        }

        private Dispatcher createDispatcherProxy() {
            return (Dispatcher)
                    Proxy.newProxyInstance(
                            Dispatcher.class.getClassLoader(),
                            new Class<?>[] {Dispatcher.class},
                            (p, method, args) -> {
                                switch (method.getName()) {
                                    case "subscribe" -> {
                                        subscribedSubjects.add((String) args[0]);
                                        return p;
                                    }
                                    case "isActive" -> {
                                        return dispatcherActive.get();
                                    }
                                    default -> {
                                        return null;
                                    }
                                }
                            });
        }

        void fireEvent(ConnectionListener.Events event) {
            if (listener != null) {
                listener.connectionEvent(proxy, event);
            }
        }
    }

    @Test
    void flushFailureStaysDegradedReleasesResourcesAndRetriesUntilConfirmed() throws Exception {
        var attempt = new AtomicInteger();
        var connections = new CopyOnWriteArrayList<FakeNatsConnection>();
        var states = new CopyOnWriteArrayList<WakeupState>();
        var connectedLatch = new CountDownLatch(1);

        try (var transport =
                new NatsWakeupTransport(
                        fastOpts(),
                        options -> {
                            var fake = new FakeNatsConnection(options);
                            connections.add(fake);
                            if (attempt.incrementAndGet() == 1) {
                                fake.flushAction.set(
                                        () -> {
                                            throw new TimeoutException("simulated flush timeout");
                                        });
                            }
                            return fake.proxy;
                        })) {
            WakeupKey key = new WakeupKey(ConsumableFamily.COMMAND, 0);
            transport.start(
                    Set.of(key),
                    k -> {},
                    s -> {
                        states.add(s);
                        if (s == WakeupState.CONNECTED) connectedLatch.countDown();
                    });

            assertTrue(
                    connectedLatch.await(5, TimeUnit.SECONDS),
                    "Transport must retry and reach CONNECTED after flush succeeds");
            assertEquals(WakeupState.CONNECTED, transport.state());
            assertTrue(transport.isSubscriptionsConfirmed());
            assertTrue(connections.size() >= 2, "Must have retried after first flush failure");

            FakeNatsConnection first = connections.getFirst();
            assertEquals(1, first.dispatchersCreated.get());
            assertEquals(1, first.dispatchersClosed.get(), "Failed dispatcher must be closed");
            assertEquals(1, first.connectionClosed.get(), "Failed connection must be closed");

            FakeNatsConnection second = connections.get(1);
            assertEquals(1, second.dispatchersCreated.get());
            assertEquals(0, second.dispatchersClosed.get());
            assertEquals(List.of(key.subject()), second.subscribedSubjects);
        }
    }

    @Test
    void noPrematureConnectedWhileFlushIsInFlight() throws Exception {
        var enteredFlush = new CountDownLatch(1);
        var releaseFlush = new CountDownLatch(1);
        var connectedLatch = new CountDownLatch(1);
        var statesBeforeRelease = new CopyOnWriteArrayList<WakeupState>();

        try (var transport =
                new NatsWakeupTransport(
                        fastOpts(),
                        options -> {
                            var fake = new FakeNatsConnection(options);
                            fake.flushAction.set(
                                    () -> {
                                        enteredFlush.countDown();
                                        assertTrue(releaseFlush.await(5, TimeUnit.SECONDS));
                                    });
                            return fake.proxy;
                        })) {
            transport.start(
                    Set.of(new WakeupKey(ConsumableFamily.POT_EVENT, 1)),
                    k -> {},
                    s -> {
                        statesBeforeRelease.add(s);
                        if (s == WakeupState.CONNECTED) connectedLatch.countDown();
                    });

            assertTrue(enteredFlush.await(5, TimeUnit.SECONDS), "Must enter flush()");
            // Connection is CONNECTED, subscribe() ran, but flush() has NOT returned yet!
            assertEquals(WakeupState.DEGRADED, transport.state());
            assertFalse(transport.isSubscriptionsConfirmed());
            assertFalse(
                    statesBeforeRelease.contains(WakeupState.CONNECTED),
                    "Must never announce CONNECTED before flush() confirms subscriptions");

            releaseFlush.countDown();
            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS));
            assertEquals(WakeupState.CONNECTED, transport.state());
            assertTrue(transport.isSubscriptionsConfirmed());
        }
    }

    @Test
    void disconnectDuringSubscriptionInstallationNeverAnnouncesConnectedAndCleansUp()
            throws Exception {
        var attempt = new AtomicInteger();
        var connections = new CopyOnWriteArrayList<FakeNatsConnection>();
        var connectedLatch = new CountDownLatch(1);

        try (var transport =
                new NatsWakeupTransport(
                        fastOpts(),
                        options -> {
                            var fake = new FakeNatsConnection(options);
                            connections.add(fake);
                            if (attempt.incrementAndGet() == 1) {
                                // Simulate disconnect happening during flush()
                                fake.onFlush.set(
                                        () -> fake.status.set(Connection.Status.DISCONNECTED));
                            }
                            return fake.proxy;
                        })) {
            transport.start(
                    Set.of(new WakeupKey(ConsumableFamily.IDENTITY_REQUEST, 0)),
                    k -> {},
                    s -> {
                        if (s == WakeupState.CONNECTED) connectedLatch.countDown();
                    });

            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS));
            assertTrue(connections.size() >= 2);
            FakeNatsConnection first = connections.getFirst();
            assertEquals(1, first.dispatchersClosed.get());
            assertEquals(1, first.connectionClosed.get());
            assertEquals(WakeupState.CONNECTED, transport.state());
        }
    }

    @Test
    void unconfirmedInactiveDispatcherNeverAnnouncesConnected() throws Exception {
        var attempt = new AtomicInteger();
        var connections = new CopyOnWriteArrayList<FakeNatsConnection>();
        var connectedLatch = new CountDownLatch(1);

        try (var transport =
                new NatsWakeupTransport(
                        fastOpts(),
                        options -> {
                            var fake = new FakeNatsConnection(options);
                            connections.add(fake);
                            if (attempt.incrementAndGet() == 1) {
                                fake.dispatcherActive.set(false);
                            }
                            return fake.proxy;
                        })) {
            transport.start(
                    Set.of(new WakeupKey(ConsumableFamily.COMMAND, 2)),
                    k -> {},
                    s -> {
                        if (s == WakeupState.CONNECTED) connectedLatch.countDown();
                    });

            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS));
            assertTrue(connections.size() >= 2);
            assertEquals(1, connections.getFirst().dispatchersClosed.get());
            assertEquals(1, connections.getFirst().connectionClosed.get());
        }
    }

    @Test
    void reconnectWaitsForFlushConfirmationAndNeverCreatesDuplicateDispatchers() throws Exception {
        var fakeRef = new AtomicReference<FakeNatsConnection>();
        var firstConnected = new CountDownLatch(1);
        var degradedAfterDisconnect = new CountDownLatch(1);
        var reconnectFlushEntered = new CountDownLatch(1);
        var releaseReconnectFlush = new CountDownLatch(1);
        var reconnected = new CountDownLatch(1);

        try (var transport =
                new NatsWakeupTransport(
                        fastOpts(),
                        options -> {
                            var fake = new FakeNatsConnection(options);
                            fakeRef.set(fake);
                            return fake.proxy;
                        })) {
            transport.start(
                    Set.of(new WakeupKey(ConsumableFamily.POT_EVENT, 0)),
                    k -> {},
                    s -> {
                        if (s == WakeupState.CONNECTED) {
                            if (firstConnected.getCount() > 0) {
                                firstConnected.countDown();
                            } else if (degradedAfterDisconnect.getCount() == 0) {
                                reconnected.countDown();
                            }
                        } else if (s == WakeupState.DEGRADED && firstConnected.getCount() == 0) {
                            degradedAfterDisconnect.countDown();
                        }
                    });

            assertTrue(firstConnected.await(5, TimeUnit.SECONDS));
            FakeNatsConnection fake = fakeRef.get();
            assertEquals(1, fake.dispatchersCreated.get());
            assertEquals(1, fake.flushCalls.get());

            // Simulate disconnect
            fake.status.set(Connection.Status.DISCONNECTED);
            fake.fireEvent(ConnectionListener.Events.DISCONNECTED);
            assertTrue(degradedAfterDisconnect.await(5, TimeUnit.SECONDS));
            assertEquals(WakeupState.DEGRADED, transport.state());
            assertFalse(transport.isSubscriptionsConfirmed());

            // Hold reconnect flush to verify no premature CONNECTED
            fake.flushAction.set(
                    () -> {
                        reconnectFlushEntered.countDown();
                        assertTrue(releaseReconnectFlush.await(5, TimeUnit.SECONDS));
                    });
            fake.status.set(Connection.Status.CONNECTED);
            fake.fireEvent(ConnectionListener.Events.RECONNECTED);

            assertTrue(reconnectFlushEntered.await(5, TimeUnit.SECONDS));
            assertEquals(
                    WakeupState.DEGRADED,
                    transport.state(),
                    "Must remain DEGRADED during reconnect flush confirmation");
            assertFalse(transport.isSubscriptionsConfirmed());

            releaseReconnectFlush.countDown();
            assertTrue(reconnected.await(5, TimeUnit.SECONDS));
            assertEquals(WakeupState.CONNECTED, transport.state());
            assertTrue(transport.isSubscriptionsConfirmed());
            assertEquals(
                    1,
                    fake.dispatchersCreated.get(),
                    "Reconnect must reuse jnats auto-resubscribed dispatcher without duplicating");
            assertEquals(2, fake.flushCalls.get(), "Reconnect must confirm subscriptions via flush()");
        }
    }

    @Test
    void startAfterPublisherConnectResetsAndRetriesWhenSubscriptionFlushFails() throws Exception {
        var attempt = new AtomicInteger();
        var connections = new CopyOnWriteArrayList<FakeNatsConnection>();
        var connectedLatch = new CountDownLatch(1);

        try (var transport =
                new NatsWakeupTransport(
                        fastOpts(),
                        options -> {
                            var fake = new FakeNatsConnection(options);
                            connections.add(fake);
                            if (attempt.incrementAndGet() == 1) {
                                // First connection connects as publisher-only; when start() flushes, fail!
                                fake.flushAction.set(
                                        () -> {
                                            throw new TimeoutException("start() flush timeout");
                                        });
                            }
                            return fake.proxy;
                        })) {
            // Publisher connects first without subscriber
            try (var pub =
                    transport.createPublisher(
                            CoalescingWakeupPublisher.Strategy.IN_FLIGHT_DEDUP,
                            1L,
                            WakeupMetrics.NOOP)) {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (transport.state() != WakeupState.CONNECTED && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(WakeupState.CONNECTED, transport.state());
                assertEquals(1, connections.size());

                // Now attach subscriber -> flush fails on existing connection -> must drop to DEGRADED and retry
                var states = new CopyOnWriteArrayList<WakeupState>();
                transport.start(
                        Set.of(new WakeupKey(ConsumableFamily.COMMAND, 1)),
                        k -> {},
                        s -> {
                            states.add(s);
                            if (s == WakeupState.CONNECTED) connectedLatch.countDown();
                        });

                assertTrue(states.contains(WakeupState.DEGRADED));
                assertTrue(connectedLatch.await(5, TimeUnit.SECONDS));
                assertEquals(WakeupState.CONNECTED, transport.state());
                assertTrue(connections.size() >= 2);
                assertEquals(1, connections.getFirst().connectionClosed.get());
            }
        }
    }
}
