package com.kartaguez.pocoma.supra.http.write.v3;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.kartaguez.pocoma.contracts.command.v3.AdmissionFailure;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

/** One bounded permit through parsing, physical SQL termination and synchronous response send. */
public final class V3AdmissionTransport extends OncePerRequestFilter {
    public static final String UNRESOLVED = V3AdmissionTransport.class.getName() + ".unresolved";
    public static final String BODY = V3AdmissionTransport.class.getName() + ".body";

    public record Limits(
            int commandBytes,
            int identityBytes,
            int depth,
            int textBytes,
            int rationalDigits,
            int capacity) {
        public Limits {
            if (commandBytes < 1
                    || identityBytes < 1
                    || depth < 1
                    || textBytes < 1
                    || rationalDigits < 1
                    || capacity < 1)
                throw new IllegalArgumentException("Positive admission bounds required");
        }

        public static Limits initial() {
            return new Limits(262144, 16384, 16, 4096, 1024, 1);
        }
    }

    private final Limits limits;
    private final Semaphore capacity;
    private final ObjectMapper parser;

    public V3AdmissionTransport(Limits limits) {
        this.limits = limits;
        capacity = new Semaphore(limits.capacity());
        parser =
                new ObjectMapper(
                                JsonFactory.builder()
                                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                        .streamReadConstraints(
                                                StreamReadConstraints.builder()
                                                        .maxNestingDepth(limits.depth())
                                                        .maxStringLength(
                                                                Math.max(
                                                                        limits.commandBytes(),
                                                                        limits.identityBytes()))
                                                        .maxNumberLength(20)
                                                        .build())
                                        .build())
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public Limits limits() {
        return limits;
    }

    public int availableCapacity() {
        return capacity.availablePermits();
    }

    public Object parse(byte[] body) {
        try {
            return parser.readValue(body, Object.class);
        } catch (Exception invalid) {
            throw new AdmissionFailure(400);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !Set.of("/api/v3/commands", "/api/v3/identity/requests").contains(r.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest r, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        if (!capacity.tryAcquire()) {
            error(response, 503);
            return;
        }
        try {
            AdmissionFailure.require(r.getMethod().equals("POST") && r.getQueryString() == null);
            if (r.getHeader("Content-Encoding") != null) throw new AdmissionFailure(415);
            String media = r.getContentType();
            if (media == null
                    || !media.toLowerCase(Locale.ROOT)
                            .matches("application/json(?:\\s*;\\s*charset=utf-8)?"))
                throw new AdmissionFailure(415);
            int max =
                    r.getRequestURI().equals("/api/v3/commands")
                            ? limits.commandBytes()
                            : limits.identityBytes();
            if (r.getContentLengthLong() > max) throw new AdmissionFailure(413);
            byte[] body = r.getInputStream().readNBytes(max + 1);
            if (body.length > max) throw new AdmissionFailure(413);
            r.setAttribute(BODY, body);
            chain.doFilter(r, response);
        } catch (AdmissionFailure failure) {
            if (!response.isCommitted()) error(response, failure.status());
        } finally {
            r.removeAttribute(BODY);
            if (!Boolean.TRUE.equals(r.getAttribute(UNRESOLVED))) capacity.release();
            r.removeAttribute(UNRESOLVED);
        }
    }

    public static void error(HttpServletResponse response, int status) throws IOException {
        String code =
                switch (status) {
                    case 400 -> "INVALID_INPUT";
                    case 401 -> "INVALID_TOKEN";
                    case 409 -> "IDEMPOTENCY_CONFLICT";
                    case 413 -> "PAYLOAD_TOO_LARGE";
                    case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                    default -> "UNAVAILABLE";
                };
        byte[] body =
                ("{\"code\":\""
                                + code
                                + "\",\"message\":\"Request unavailable\",\"traceId\":\""
                                + UUID.randomUUID()
                                + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
        response.resetBuffer();
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
