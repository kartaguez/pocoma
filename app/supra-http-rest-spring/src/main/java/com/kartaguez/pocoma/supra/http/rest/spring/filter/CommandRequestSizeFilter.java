package com.kartaguez.pocoma.supra.http.rest.spring.filter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

@Component
@ConditionalOnProperty(prefix = "pocoma.command-admission", name = "enabled", havingValue = "true")
public final class CommandRequestSizeFilter extends OncePerRequestFilter {
	private static final String PATH = "/api/v1/commands";
	private final long maximumBytes;
	private final ObjectMapper objectMapper;

	public CommandRequestSizeFilter(
			@Value("${pocoma.command-admission.max-request-bytes:262144}") long maximumBytes,
			ObjectMapper objectMapper) {
		if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes must be positive");
		this.maximumBytes = maximumBytes;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (request.getContentLengthLong() > maximumBytes) {
			writeTooLarge(request, response);
			return;
		}
		try {
			filterChain.doFilter(new LimitedRequest(request, maximumBytes), response);
		}
		catch (CommandRequestTooLargeException exception) {
			if (!response.isCommitted()) writeTooLarge(request, response);
		}
	}

	private void writeTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
				"COMMAND_PAYLOAD_TOO_LARGE", "Command request exceeds the configured limit",
				HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, request.getRequestURI()));
	}

	private static final class LimitedRequest extends HttpServletRequestWrapper {
		private final long maximumBytes;
		private LimitedRequest(HttpServletRequest request, long maximumBytes) {
			super(request);
			this.maximumBytes = maximumBytes;
		}
		@Override public ServletInputStream getInputStream() throws IOException {
			return new LimitedServletInputStream(super.getInputStream(), maximumBytes);
		}
		@Override public BufferedReader getReader() throws IOException {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}
	}

	private static final class LimitedServletInputStream extends ServletInputStream {
		private final ServletInputStream delegate;
		private final long maximumBytes;
		private long read;
		private LimitedServletInputStream(ServletInputStream delegate, long maximumBytes) {
			this.delegate = delegate;
			this.maximumBytes = maximumBytes;
		}
		@Override public int read() throws IOException {
			int value = delegate.read();
			if (value >= 0 && ++read > maximumBytes) throw new CommandRequestTooLargeException();
			return value;
		}
		@Override public int read(byte[] bytes, int offset, int length) throws IOException {
			int count = delegate.read(bytes, offset, length);
			if (count > 0 && (read += count) > maximumBytes) throw new CommandRequestTooLargeException();
			return count;
		}
		@Override public boolean isFinished() { return delegate.isFinished(); }
		@Override public boolean isReady() { return delegate.isReady(); }
		@Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
	}

}
