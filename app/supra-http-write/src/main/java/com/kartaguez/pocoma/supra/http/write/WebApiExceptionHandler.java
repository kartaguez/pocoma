package com.kartaguez.pocoma.supra.http.write;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.kartaguez.pocoma.engine.admit.command.ExpiredAuthenticatedPrincipalException;
import com.kartaguez.pocoma.engine.admit.command.InvalidAuthenticatedExternalPrincipalException;
import com.kartaguez.pocoma.supra.http.write.InvalidRequestException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class WebApiExceptionHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(WebApiExceptionHandler.class);

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<ApiErrorResponse> invalid(InvalidRequestException exception, HttpServletRequest request) {
		return error(exception.code(), exception.getMessage(), HttpStatus.BAD_REQUEST, request);
	}
	@ExceptionHandler({IllegalArgumentException.class, NullPointerException.class,
			MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
	ResponseEntity<ApiErrorResponse> badRequest(Exception exception, HttpServletRequest request) {
		return error("INVALID_REQUEST", exception.getMessage(), HttpStatus.BAD_REQUEST, request);
	}
	@ExceptionHandler({InvalidAuthenticatedExternalPrincipalException.class, ExpiredAuthenticatedPrincipalException.class})
	ResponseEntity<ApiErrorResponse> invalidPrincipal(RuntimeException exception, HttpServletRequest request) {
		return error("INVALID_AUTHENTICATED_PRINCIPAL", exception.getMessage(), HttpStatus.UNAUTHORIZED, request);
	}
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ApiErrorResponse> unsupported(HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
		return error("UNSUPPORTED_MEDIA_TYPE", exception.getMessage(), HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
	}
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
		LOGGER.error("Unexpected HTTP request failure", exception);
		return error("INTERNAL_ERROR", "Unexpected error", HttpStatus.INTERNAL_SERVER_ERROR, request);
	}
	private static ResponseEntity<ApiErrorResponse> error(String code, String message, HttpStatus status,
			HttpServletRequest request) {
		return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, status.value(), request.getRequestURI()));
	}
}
