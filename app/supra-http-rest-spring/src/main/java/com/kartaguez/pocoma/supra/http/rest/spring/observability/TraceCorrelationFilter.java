package com.kartaguez.pocoma.supra.http.rest.spring.observability;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.kartaguez.pocoma.observability.trace.TraceContext;
import com.kartaguez.pocoma.observability.trace.TraceContextHolder;
import com.kartaguez.pocoma.supra.http.rest.spring.security.UserContextFactory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class TraceCorrelationFilter extends OncePerRequestFilter {

	public static final String TRACE_ID_HEADER = "X-Trace-Id";

	private static final Logger LOGGER = LoggerFactory.getLogger(TraceCorrelationFilter.class);

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		long startedAtNanos = System.nanoTime();
		String traceId = traceId(request);
		String userId = request.getHeader(UserContextFactory.USER_ID_HEADER);
		String method = request.getMethod();
		String path = request.getRequestURI();
		String operation = operation(method, path);

		response.setHeader(TRACE_ID_HEADER, traceId);
		putMdc(traceId, userId, method, path, operation);
		TraceContextHolder.set(new TraceContext(traceId, userId, method, path, operation, startedAtNanos, null));

		LOGGER.info("HTTP operation started");
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			long durationNanos = System.nanoTime() - startedAtNanos;
			MDC.put("http.status", Integer.toString(response.getStatus()));
			MDC.put("duration_ms", Long.toString(durationNanos / 1_000_000L));
			LOGGER.info("HTTP operation completed");
			TraceContextHolder.clear();
			MDC.clear();
		}
	}

	private static void putMdc(String traceId, String userId, String method, String path, String operation) {
		MDC.put("traceId", traceId);
		if (userId != null && !userId.isBlank()) {
			MDC.put("userId", userId);
		}
		MDC.put("http.method", method);
		MDC.put("http.path", path);
		MDC.put("operation", operation);
	}

	private static String traceId(HttpServletRequest request) {
		String incomingTraceId = request.getHeader(TRACE_ID_HEADER);
		if (incomingTraceId != null && !incomingTraceId.isBlank()) {
			return incomingTraceId;
		}
		return UUID.randomUUID().toString();
	}

	private static String operation(String method, String path) {
		if (!path.startsWith("/api/")) {
			return "http." + method.toLowerCase();
		}
		if ("GET".equals(method)) {
			return "query";
		}
		return "command";
	}
}
