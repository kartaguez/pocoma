package com.kartaguez.pocoma;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kartaguez.pocoma.contracts.observability.trace.TraceContext;
import com.kartaguez.pocoma.contracts.observability.trace.TraceContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class TraceCorrelationFilter extends OncePerRequestFilter {
	public static final String TRACE_ID_HEADER = "X-Trace-Id";
	private static final Logger LOGGER = LoggerFactory.getLogger(TraceCorrelationFilter.class);

	@Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		long startedAtNanos = System.nanoTime();
		String traceId = request.getHeader(TRACE_ID_HEADER);
		if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString();
		String method = request.getMethod();
		String path = request.getRequestURI();
		String operation = path.startsWith("/api/") ? ("GET".equals(method) ? "query" : "command")
				: "http." + method.toLowerCase();
		response.setHeader(TRACE_ID_HEADER, traceId);
		MDC.put("traceId", traceId); MDC.put("http.method", method); MDC.put("http.path", path);
		MDC.put("operation", operation);
		TraceContextHolder.set(new TraceContext(traceId, null, method, path, operation, startedAtNanos, null));
		LOGGER.info("HTTP operation started");
		try { filterChain.doFilter(request, response); }
		finally {
			MDC.put("http.status", Integer.toString(response.getStatus()));
			MDC.put("duration_ms", Long.toString((System.nanoTime() - startedAtNanos) / 1_000_000L));
			LOGGER.info("HTTP operation completed");
			TraceContextHolder.clear(); MDC.clear();
		}
	}
}
