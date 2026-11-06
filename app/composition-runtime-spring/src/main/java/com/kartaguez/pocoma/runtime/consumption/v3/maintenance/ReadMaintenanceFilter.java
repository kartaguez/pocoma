package com.kartaguez.pocoma.runtime.consumption.v3.maintenance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fail-closed HTTP barrier filter for READ façade.
 * Intercepts /api/v3/* requests when READ is in MAINTENANCE, REBUILDING, FAILED or unreadable,
 * returning HTTP 503 with Retry-After: 30.
 */
public final class ReadMaintenanceFilter extends OncePerRequestFilter {

    public static final Duration CACHE_TTL = Duration.ofMillis(500);

    private final ReadMaintenanceAuthority authority;
    private final boolean admissionActive;
    private final AtomicInteger inFlightRequests = new AtomicInteger();

    private record CachedState(ReadMaintenanceState state, long cachedAtNanos) {}
    private volatile CachedState cached;

    public ReadMaintenanceFilter(ReadMaintenanceAuthority authority) {
        this(authority, false);
    }

    public ReadMaintenanceFilter(ReadMaintenanceAuthority authority, boolean admissionActive) {
        this.authority = Objects.requireNonNull(authority, "authority cannot be null");
        this.admissionActive = admissionActive;
    }

    public int inFlightRequests() {
        return inFlightRequests.get();
    }

    public void invalidateCache() {
        this.cached = null;
    }

    public boolean awaitDrain(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlightRequests.get() > 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    public ReadMaintenanceState getEffectiveState() {
        long now = System.nanoTime();
        CachedState c = this.cached;
        if (c != null && (now - c.cachedAtNanos()) < CACHE_TTL.toNanos()) {
            return c.state();
        }

        ReadMaintenanceState fresh;
        try {
            fresh = authority.currentState();
            if (fresh == null) {
                fresh = ReadMaintenanceState.failed("Authority returned null state");
            }
        } catch (Exception ex) {
            // Absolute fail-closed behavior on authority or connection error
            fresh = ReadMaintenanceState.failed("Authority query failed: " + ex.getMessage());
        }

        this.cached = new CachedState(fresh, now);
        return fresh;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/v3/")) {
            return true;
        }
        if (admissionActive) {
            if (Set.of("/api/v3/commands", "/api/v3/identity/requests").contains(uri)
                    || uri.equals("/api/v3/identity/link-requests")
                    || uri.startsWith("/api/v3/identity/link-requests/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        ReadMaintenanceState state = getEffectiveState();
        if (!state.isReady()) {
            response.resetBuffer();
            response.setStatus(503);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Retry-After", "30");

            byte[] body = ("{\"code\":\"UNAVAILABLE\",\"message\":\"Request unavailable\",\"traceId\":\""
                    + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.flushBuffer();
            return;
        }

        inFlightRequests.incrementAndGet();
        try {
            filterChain.doFilter(request, response);
        } finally {
            inFlightRequests.decrementAndGet();
        }
    }
}
