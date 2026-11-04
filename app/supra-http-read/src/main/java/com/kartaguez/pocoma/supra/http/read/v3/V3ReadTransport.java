package com.kartaguez.pocoma.supra.http.read.v3;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Capacity is held through physical synchronous send/abort, independently of SQL quota. */
public final class V3ReadTransport extends OncePerRequestFilter {
    public static final int INPUT_BYTES = 65_536, OUTPUT_BYTES = 4_194_304, HTTP_PERMITS = 2;
    public static final String BODY = V3ReadTransport.class.getName() + ".body";
    private final Semaphore capacity = new Semaphore(HTTP_PERMITS);
    private final ObjectMapper mapper;
    public V3ReadTransport() {
        this(new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }
    public V3ReadTransport(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }
    public ObjectMapper mapper() { return mapper; }
    public int availableCapacity() { return capacity.availablePermits(); }
    public static final class Failure extends RuntimeException {
        public final int status;
        public Failure(int status) { super("READ request refused", null, false, false); this.status = status; }
    }
    public static void require(boolean condition) { if (!condition) throw new Failure(400); }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v3/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!capacity.tryAcquire()) { error(response, 503); return; }
        try {
            require(request.getRequestURI().length() + (request.getQueryString() == null ? 0 : 1 + request.getQueryString().length()) <= 2048);
            if (request.getHeader("Content-Encoding") != null) throw new Failure(415);
            if (request.getMethod().equals("POST")) {
                String media = request.getContentType();
                if (media == null || !media.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?"))
                    throw new Failure(415);
            }
            // Count received bytes, including chunked input; never trust Content-Length alone.
            if (request.getContentLengthLong() > INPUT_BYTES) throw new Failure(413);
            byte[] body = request.getInputStream().readNBytes(INPUT_BYTES + 1);
            if (body.length > INPUT_BYTES) throw new Failure(413);
            if (request.getMethod().equals("GET")) require(body.length == 0);
            request.setAttribute(BODY, body);
            chain.doFilter(request, response);
        } catch (Failure failure) {
            if (!response.isCommitted()) error(response, failure.status);
        } finally {
            request.removeAttribute(BODY);
            capacity.release();
        }
    }
    public void write(HttpServletResponse response, Object dto) throws IOException {
        // Segmented bounded buffer: no toByteArray copy and no second serialization on send.
        var buffer = new BoundedBytes();
        try { mapper.writeValue(buffer, dto); }
        catch (Exception failedEncoding) { error(response, 503); return; }
        response.setStatus(200);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(buffer.size);
        buffer.send(response.getOutputStream());
        response.flushBuffer();
    }
    public static void error(HttpServletResponse response, int status) throws IOException {
        String code = switch (status) {
            case 400 -> "INVALID_INPUT"; case 401 -> "INVALID_TOKEN"; case 404 -> "NOT_FOUND";
            case 413 -> "PAYLOAD_TOO_LARGE"; case 415 -> "UNSUPPORTED_MEDIA_TYPE"; default -> "UNAVAILABLE";
        };
        byte[] bytes = ("{\"code\":\"" + code + "\",\"message\":\"Request unavailable\",\"traceId\":\""
                + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
        response.resetBuffer();
        response.setStatus(status); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store"); response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
    private static final class BoundedBytes extends OutputStream {
        static final int SEGMENT = 16_384;
        final List<byte[]> segments = new ArrayList<>();
        int size;
        @Override public void write(int value) throws IOException {
            if (size == OUTPUT_BYTES) throw new IOException("READ output limit");
            if (size % SEGMENT == 0) segments.add(new byte[SEGMENT]);
            segments.get(size / SEGMENT)[size % SEGMENT] = (byte) value; size++;
        }
        @Override public void write(byte[] values, int offset, int count) throws IOException {
            if (count > OUTPUT_BYTES - size) throw new IOException("READ output limit");
            while (count > 0) {
                if (size % SEGMENT == 0) segments.add(new byte[SEGMENT]);
                int take = Math.min(count, SEGMENT - size % SEGMENT);
                System.arraycopy(values, offset, segments.get(size / SEGMENT), size % SEGMENT, take);
                size += take; offset += take; count -= take;
            }
        }
        void send(OutputStream out) throws IOException {
            int remaining = size;
            for (byte[] segment : segments) { int take = Math.min(remaining, SEGMENT); out.write(segment, 0, take); remaining -= take; }
        }
    }
}
