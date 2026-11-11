package com.kartaguez.pocoma.supra.web.read.v3.maintenance;
import com.kartaguez.pocoma.supra.web.read.v3.maintenance.ReadMaintenanceFilter;
import com.kartaguez.pocoma.infra.postgres.read.v3.maintenance.JdbcReadMaintenanceAuthority;
import com.kartaguez.pocoma.port.projection.v3.maintenance.ReadMaintenanceAuthority;
import com.kartaguez.pocoma.port.projection.v3.maintenance.ReadMaintenanceState;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadMaintenanceBarrierTest {

    private ReadMaintenanceAuthority authority;

    @BeforeEach
    void setUp() {
        authority = mock(ReadMaintenanceAuthority.class);
    }

    @Test
    void whenStateIsReady_requestPassesThrough() throws Exception {
        when(authority.currentState()).thenReturn(ReadMaintenanceState.initialReady());
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/me/binding");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        assertEquals(0, filter.inFlightRequests());
    }

    @Test
    void whenStateIsMaintenance_returns503WithRetryAfter30() throws Exception {
        when(authority.currentState()).thenReturn(new ReadMaintenanceState(
                ReadMaintenanceState.State.MAINTENANCE,
                ReadMaintenanceState.Phase.DRAINING,
                Map.of(), "op-1", Instant.now(), Instant.now(), null
        ));
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/pots/123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getContentAsString().contains("\"code\":\"UNAVAILABLE\""));
        assertNull(chain.getRequest(), "Downstream chain should not have been called");
    }

    @Test
    void whenStateIsRebuilding_returns503WithRetryAfter30() throws Exception {
        when(authority.currentState()).thenReturn(new ReadMaintenanceState(
                ReadMaintenanceState.State.REBUILDING,
                ReadMaintenanceState.Phase.REPLAYING,
                Map.of("POT", 42L), "op-1", Instant.now(), Instant.now(), null
        ));
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/me/pots");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        assertNull(chain.getRequest());
    }

    @Test
    void whenStateIsFailed_returns503WithRetryAfter30() throws Exception {
        when(authority.currentState()).thenReturn(ReadMaintenanceState.failed("Fatal DB corruption"));
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/me/binding");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        // Ensure internal error details are not leaked in the response
        assertFalse(response.getContentAsString().contains("Fatal DB corruption"));
        assertTrue(response.getContentAsString().contains("\"message\":\"Request unavailable\""));
        assertNull(chain.getRequest());
    }

    @Test
    void whenAuthorityThrowsException_failsClosedWith503() throws Exception {
        when(authority.currentState()).thenThrow(new RuntimeException("PostgreSQL connection refused"));
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v3/pots/123/versions/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("30", response.getHeader("Retry-After"));
        assertNull(chain.getRequest());
    }

    @Test
    void cacheTtl500ms_avoidsRepeatedDatabaseCalls() throws Exception {
        when(authority.currentState()).thenReturn(ReadMaintenanceState.initialReady());
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);

        MockHttpServletRequest request1 = new MockHttpServletRequest("GET", "/api/v3/me/binding");
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        filter.doFilter(request1, response1, new MockFilterChain());

        MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/api/v3/me/binding");
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        filter.doFilter(request2, response2, new MockFilterChain());

        // Called only once due to 500ms cache TTL
        verify(authority, times(1)).currentState();

        // Invalidate cache and call again
        filter.invalidateCache();
        MockHttpServletRequest request3 = new MockHttpServletRequest("GET", "/api/v3/me/binding");
        MockHttpServletResponse response3 = new MockHttpServletResponse();
        filter.doFilter(request3, response3, new MockFilterChain());

        verify(authority, times(2)).currentState();
    }

    @Test
    void nonApiV3EndpointsAreNotFiltered() throws Exception {
        when(authority.currentState()).thenReturn(ReadMaintenanceState.failed("Maintenance"));
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "Non-/api/v3/ endpoints must pass through");
        assertEquals(200, response.getStatus());
    }

    @Test
    void admissionEndpointsAreNotFiltered_whenAdmissionActive() throws Exception {
        when(authority.currentState()).thenReturn(ReadMaintenanceState.failed("Maintenance"));
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority, true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v3/commands");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "Admission endpoint must not be blocked by READ maintenance");
    }

    @Test
    void inFlightRequestsAndDrain() throws Exception {
        when(authority.currentState()).thenReturn(ReadMaintenanceState.initialReady());
        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        FilterChain blockingChain = (req, res) -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                try {
                    filter.doFilter(new MockHttpServletRequest("GET", "/api/v3/me/binding"),
                            new MockHttpServletResponse(), blockingChain);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(1, filter.inFlightRequests(), "Must track in-flight request");

            // Await drain before release should timeout
            assertFalse(filter.awaitDrain(Duration.ofMillis(50)));

            release.countDown();
            assertTrue(filter.awaitDrain(Duration.ofSeconds(1)));
            assertEquals(0, filter.inFlightRequests());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void multiInstanceSimulation_concurrentRequests() throws Exception {
        AtomicInteger queryCount = new AtomicInteger();
        AtomicBoolean isMaintenance = new AtomicBoolean(false);

        when(authority.currentState()).thenAnswer(inv -> {
            queryCount.incrementAndGet();
            if (isMaintenance.get()) {
                return new ReadMaintenanceState(
                        ReadMaintenanceState.State.MAINTENANCE,
                        ReadMaintenanceState.Phase.DRAINING,
                        Map.of(), "op", Instant.now(), Instant.now(), null);
            }
            return ReadMaintenanceState.initialReady();
        });

        ReadMaintenanceFilter filter = new ReadMaintenanceFilter(authority);
        int threadCount = 8;
        int requestsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        try {
            CountDownLatch phase1Ready = new CountDownLatch(threadCount);
            CountDownLatch proceedToMaintenance = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger okCount = new AtomicInteger();
            AtomicInteger unavailableCount = new AtomicInteger();

            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    try {
                        // Wave 1: READY
                        for (int i = 0; i < requestsPerThread / 2; i++) {
                            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v3/me/binding");
                            MockHttpServletResponse res = new MockHttpServletResponse();
                            filter.doFilter(req, res, new MockFilterChain());
                            if (res.getStatus() == 200) {
                                okCount.incrementAndGet();
                            } else if (res.getStatus() == 503) {
                                unavailableCount.incrementAndGet();
                            }
                        }
                        phase1Ready.countDown();
                        proceedToMaintenance.await();

                        // Wave 2: MAINTENANCE
                        for (int i = 0; i < requestsPerThread / 2; i++) {
                            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v3/me/binding");
                            MockHttpServletResponse res = new MockHttpServletResponse();
                            filter.doFilter(req, res, new MockFilterChain());
                            if (res.getStatus() == 200) {
                                okCount.incrementAndGet();
                            } else if (res.getStatus() == 503) {
                                unavailableCount.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(phase1Ready.await(5, TimeUnit.SECONDS));
            // Transition to maintenance and invalidate cache
            isMaintenance.set(true);
            filter.invalidateCache();
            proceedToMaintenance.countDown();

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(threadCount * requestsPerThread, okCount.get() + unavailableCount.get());
            assertTrue(okCount.get() > 0, "Initial wave must succeed");
            assertTrue(unavailableCount.get() > 0, "Second wave under maintenance must return 503");
            assertEquals(0, filter.inFlightRequests());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void jdbcAuthority_tryAcquireMaintenance_usesCasConditioningOnReadyAndIdle() {
        org.springframework.jdbc.core.JdbcTemplate mockJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        java.util.UUID opId = java.util.UUID.randomUUID();
        when(mockJdbc.update(contains("WHERE id = 1 AND state = 'READY' AND phase = 'IDLE'"), eq("operator-1"), eq(opId)))
                .thenReturn(1);

        JdbcReadMaintenanceAuthority jdbcAuth = new JdbcReadMaintenanceAuthority(mockJdbc);
        assertTrue(jdbcAuth.tryAcquireMaintenance("operator-1", opId));

        // When another operator tries or state is not READY, update returns 0
        when(mockJdbc.update(contains("WHERE id = 1 AND state = 'READY' AND phase = 'IDLE'"), eq("operator-2"), any(java.util.UUID.class)))
                .thenReturn(0);
        assertFalse(jdbcAuth.tryAcquireMaintenance("operator-2", java.util.UUID.randomUUID()));
    }

    @Test
    void jdbcAuthority_operationFencing_conditionsOnOperationId() {
        org.springframework.jdbc.core.JdbcTemplate mockJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        java.util.UUID activeOpId = java.util.UUID.randomUUID();
        java.util.UUID rogueOpId = java.util.UUID.randomUUID();

        when(mockJdbc.update(contains("operation_id = ?"), eq("MIGRATED"), eq(activeOpId)))
                .thenReturn(1);
        when(mockJdbc.update(contains("operation_id = ?"), eq("MIGRATED"), eq(rogueOpId)))
                .thenReturn(0);

        JdbcReadMaintenanceAuthority jdbcAuth = new JdbcReadMaintenanceAuthority(mockJdbc);
        assertTrue(jdbcAuth.updatePhase(ReadMaintenanceState.Phase.MIGRATED, activeOpId));
        assertFalse(jdbcAuth.updatePhase(ReadMaintenanceState.Phase.MIGRATED, rogueOpId));
    }

    @Test
    void jdbcAuthority_resetFailed_requiresStateFailed() {
        org.springframework.jdbc.core.JdbcTemplate mockJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(mockJdbc.update(contains("WHERE id = 1 AND state = 'FAILED'"))).thenReturn(1);

        JdbcReadMaintenanceAuthority jdbcAuth = new JdbcReadMaintenanceAuthority(mockJdbc);
        assertTrue(jdbcAuth.resetFailed("operator-1"));
    }

    @Test
    void jdbcAuthority_tryAcquireRecovery_acceptsInterruptedOrFailed() {
        org.springframework.jdbc.core.JdbcTemplate mockJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(mockJdbc.update(contains("WHERE id = 1 AND state IN ('MAINTENANCE', 'REBUILDING', 'FAILED')"), eq("operator-1"), any(UUID.class)))
                .thenReturn(1);

        JdbcReadMaintenanceAuthority jdbcAuth = new JdbcReadMaintenanceAuthority(mockJdbc);
        assertTrue(jdbcAuth.tryAcquireRecovery("operator-1", UUID.randomUUID()));
    }

    @Test
    void jdbcAuthority_tryAcquireRecovery_rejectsReadyState() {
        org.springframework.jdbc.core.JdbcTemplate mockJdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(mockJdbc.update(contains("WHERE id = 1 AND state IN ('MAINTENANCE', 'REBUILDING', 'FAILED')"), eq("operator-1"), any(UUID.class)))
                .thenReturn(0);

        JdbcReadMaintenanceAuthority jdbcAuth = new JdbcReadMaintenanceAuthority(mockJdbc);
        assertFalse(jdbcAuth.tryAcquireRecovery("operator-1", UUID.randomUUID()));
    }
}
