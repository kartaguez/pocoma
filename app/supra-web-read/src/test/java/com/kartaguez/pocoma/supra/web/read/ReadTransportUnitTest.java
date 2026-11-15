package com.kartaguez.pocoma.supra.web.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReadTransportUnitTest {

    @Test
    void invalidRequestsAreRejectedBeforeAcquiringSemaphorePermit() throws Exception {
        var transport = new ReadTransport(false, (e, s, d) -> {}, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var chainCalledForInvalid = new AtomicBoolean(false);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlight =
                    pool.submit(
                            () -> {
                                try {
                                    var req = new MockHttpServletRequest("GET", "/api/v3/me/binding");
                                    var res = new MockHttpServletResponse();
                                    FilterChain chain =
                                            (r, s) -> {
                                                entered.countDown();
                                                try {
                                                    release.await(5, TimeUnit.SECONDS);
                                                } catch (InterruptedException ignored) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            };
                                    transport.doFilter(req, res, chain);
                                    assertEquals(200, res.getStatus());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            });

            entered.await(5, TimeUnit.SECONDS);
            assertEquals(0, transport.availableCapacity());

            // Even with 0 permits available, invalid requests must fail validation (415/413/400),
            // never 503, and never enter the filter chain.
            var badContentType = new MockHttpServletRequest("POST", "/api/v3/pots/1/expenses/query");
            badContentType.setContentType("text/plain");
            badContentType.setContent("{}".getBytes(StandardCharsets.UTF_8));
            var badContentTypeRes = new MockHttpServletResponse();
            transport.doFilter(badContentType, badContentTypeRes, (r, s) -> chainCalledForInvalid.set(true));
            assertEquals(415, badContentTypeRes.getStatus());

            var oversized = new MockHttpServletRequest("POST", "/api/v3/pots/1/expenses/query");
            oversized.setContentType("application/json");
            oversized.setContent(new byte[ReadTransport.INPUT_BYTES + 1]);
            var oversizedRes = new MockHttpServletResponse();
            transport.doFilter(oversized, oversizedRes, (r, s) -> chainCalledForInvalid.set(true));
            assertEquals(413, oversizedRes.getStatus());

            var getWithBody = new MockHttpServletRequest("GET", "/api/v3/me/binding");
            getWithBody.setContent("unexpected".getBytes(StandardCharsets.UTF_8));
            var getWithBodyRes = new MockHttpServletResponse();
            transport.doFilter(getWithBody, getWithBodyRes, (r, s) -> chainCalledForInvalid.set(true));
            assertEquals(400, getWithBodyRes.getStatus());

            // A valid request while permit is held gets 503.
            var validWhileBusy = new MockHttpServletRequest("GET", "/api/v3/me/binding");
            var validWhileBusyRes = new MockHttpServletResponse();
            transport.doFilter(validWhileBusy, validWhileBusyRes, (r, s) -> chainCalledForInvalid.set(true));
            assertEquals(503, validWhileBusyRes.getStatus());

            assertFalse(chainCalledForInvalid.get());
            release.countDown();
            inFlight.get(5, TimeUnit.SECONDS);
            assertEquals(1, transport.availableCapacity());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void configurableHttpPermitsAllowMoreThanTwoConcurrentRequests() throws Exception {
        int permits = 8;
        var transport = new ReadTransport(false, (e, s, d) -> {}, permits);
        assertEquals(permits, transport.availableCapacity());
        var entered = new CountDownLatch(permits);
        var release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(permits);
        try {
            var futures = new java.util.ArrayList<Future<Integer>>();
            for (int i = 0; i < permits; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    var req = new MockHttpServletRequest("GET", "/api/v3/me/binding");
                                    var res = new MockHttpServletResponse();
                                    transport.doFilter(
                                            req,
                                            res,
                                            (r, s) -> {
                                                entered.countDown();
                                                try {
                                                    release.await(5, TimeUnit.SECONDS);
                                                } catch (InterruptedException ignored) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            });
                                    return res.getStatus();
                                }));
            }
            entered.await(5, TimeUnit.SECONDS);
            assertEquals(0, transport.availableCapacity());

            var ninthReq = new MockHttpServletRequest("GET", "/api/v3/me/binding");
            var ninthRes = new MockHttpServletResponse();
            transport.doFilter(ninthReq, ninthRes, (r, s) -> {});
            assertEquals(503, ninthRes.getStatus());

            release.countDown();
            for (var f : futures) {
                assertEquals(200, f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(permits, transport.availableCapacity());
        } finally {
            pool.shutdownNow();
        }
    }
}
