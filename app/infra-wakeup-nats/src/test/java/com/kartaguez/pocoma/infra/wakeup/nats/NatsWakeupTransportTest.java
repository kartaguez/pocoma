package com.kartaguez.pocoma.infra.wakeup.nats;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.wakeup.CoalescingWakeupPublisher;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupKey;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupMetrics;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupState;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

class NatsWakeupTransportTest {
    private static final DockerImageName NATS_IMAGE = DockerImageName.parse("nats:2.15.3-alpine");

    @Test
    void transportOptionsParsesPropertiesAndRejectsInvalidValues() {
        Map<String, String> props =
                Map.of(
                        "pocoma.v3.wakeup.url", "nats://nats:4222",
                        "pocoma.v3.wakeup.auth-token", "secret-token",
                        "pocoma.v3.wakeup.connection-timeout-ms", "1500",
                        "pocoma.v3.wakeup.reconnect-wait-ms", "100",
                        "pocoma.v3.wakeup.ping-interval-ms", "1000",
                        "pocoma.v3.wakeup.max-pings-out", "3",
                        "pocoma.v3.wakeup.connect-retry-delay-ms", "300",
                        "pocoma.v3.wakeup.reconnect-buffer-bytes", "32768");

        var opts = NatsWakeupTransport.TransportOptions.fromProperties(props::get);
        assertEquals("nats://nats:4222", opts.url());
        assertEquals("secret-token", opts.authToken());
        assertEquals(Duration.ofMillis(1500), opts.connectionTimeout());
        assertEquals(Duration.ofMillis(100), opts.reconnectWait());
        assertEquals(Duration.ofMillis(1000), opts.pingInterval());
        assertEquals(3, opts.maxPingsOut());
        assertEquals(Duration.ofMillis(300), opts.connectRetryDelay());
        assertEquals(32768L, opts.reconnectBufferBytes());

        assertThrows(
                IllegalStateException.class,
                () -> NatsWakeupTransport.TransportOptions.fromProperties(k -> null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        NatsWakeupTransport.TransportOptions.fromProperties(
                                Map.of(
                                                "pocoma.v3.wakeup.url", "nats://nats:4222",
                                                "pocoma.v3.wakeup.ping-interval-ms", "0")
                                        ::get));
    }

    @Test
    void brokerAbsentStartsDegradedAndNeverThrowsOnPublish() throws Exception {
        var states = new CopyOnWriteArrayList<WakeupState>();
        var publishErrors = new AtomicInteger();
        var metrics =
                new WakeupMetrics() {
                    @Override
                    public void onPublishError() {
                        publishErrors.incrementAndGet();
                    }
                };
        var opts =
                new NatsWakeupTransport.TransportOptions(
                        "nats://127.0.0.1:1",
                        null,
                        Duration.ofMillis(200),
                        Duration.ofMillis(100),
                        Duration.ofMillis(500),
                        2,
                        Duration.ofMillis(200),
                        8192);
        try (var transport = new NatsWakeupTransport(opts);
                var publisher =
                        transport.createPublisher(
                                CoalescingWakeupPublisher.Strategy.IN_FLIGHT_DEDUP, 1L, metrics)) {
            transport.start(
                    Set.of(new WakeupKey(ConsumableFamily.COMMAND, 0)), k -> {}, states::add);
            assertEquals(WakeupState.DEGRADED, transport.state());
            assertDoesNotThrow(() -> publisher.publish(ConsumableFamily.COMMAND, 0));
            // Allow background coalescer to attempt publish and observe transport failure.
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (publishErrors.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(publishErrors.get() >= 1, "Expected publish error metric when broker absent");
            assertTrue(states.contains(WakeupState.DEGRADED));
        }
    }

    @Test
    void coreNatsPublishesReceivesAuthenticatesAndHandlesDisconnect() throws Exception {
        String token = "pocoma-test-token-42";
        try (GenericContainer<?> nats =
                new GenericContainer<>(NATS_IMAGE)
                        .withCommand("-auth", token, "-m", "8222")
                        .withExposedPorts(4222, 8222)
                        .waitingFor(Wait.forHttp("/healthz").forPort(8222))) {
            nats.start();
            String url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);

            // 1. Wrong auth token remains DEGRADED and never throws to caller.
            var badOpts =
                    new NatsWakeupTransport.TransportOptions(
                            url,
                            "wrong-token",
                            Duration.ofMillis(500),
                            Duration.ofMillis(100),
                            Duration.ofMillis(500),
                            2,
                            Duration.ofMillis(200),
                            8192);
            try (var badTransport = new NatsWakeupTransport(badOpts)) {
                badTransport.start(
                        Set.of(new WakeupKey(ConsumableFamily.COMMAND, 0)), k -> {}, s -> {});
                Thread.sleep(400);
                assertEquals(WakeupState.DEGRADED, badTransport.state());
            }

            // 2. Valid auth token connects, delivers wakeup, and transitions to DEGRADED on broker stop.
            var connectedLatch = new CountDownLatch(1);
            var degradedLatch = new CountDownLatch(1);
            var wakeupLatch = new CountDownLatch(1);
            var receivedKeys = new CopyOnWriteArrayList<WakeupKey>();

            var goodOpts =
                    new NatsWakeupTransport.TransportOptions(
                            url,
                            token,
                            Duration.ofSeconds(2),
                            Duration.ofMillis(100),
                            Duration.ofMillis(500),
                            2,
                            Duration.ofMillis(150),
                            16384);
            try (var subTransport = new NatsWakeupTransport(goodOpts);
                    var pubTransport = new NatsWakeupTransport(goodOpts);
                    var publisher =
                            pubTransport.createPublisher(
                                    CoalescingWakeupPublisher.Strategy.IN_FLIGHT_DEDUP,
                                    1L,
                                    WakeupMetrics.NOOP)) {
                WakeupKey target = new WakeupKey(ConsumableFamily.POT_EVENT, 2);
                subTransport.start(
                        Set.of(target),
                        key -> {
                            receivedKeys.add(key);
                            wakeupLatch.countDown();
                        },
                        state -> {
                            if (state == WakeupState.CONNECTED) connectedLatch.countDown();
                            if (state == WakeupState.DEGRADED && connectedLatch.getCount() == 0) {
                                degradedLatch.countDown();
                            }
                        });

                assertTrue(connectedLatch.await(10, TimeUnit.SECONDS), "Subscriber should connect");
                long pubDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (pubTransport.state() != WakeupState.CONNECTED
                        && System.nanoTime() < pubDeadline) {
                    Thread.sleep(25);
                }
                assertEquals(WakeupState.CONNECTED, pubTransport.state());

                publisher.publish(ConsumableFamily.POT_EVENT, 2);
                assertTrue(wakeupLatch.await(5, TimeUnit.SECONDS), "Wakeup notification should arrive");
                assertTrue(receivedKeys.contains(target));

                // Stop NATS abruptly -> subscriber must transition to DEGRADED.
                nats.stop();
                assertTrue(
                        degradedLatch.await(10, TimeUnit.SECONDS),
                        "Subscriber must transition to DEGRADED when NATS stops");
                assertEquals(WakeupState.DEGRADED, subTransport.state());
            }
        }
    }
}
