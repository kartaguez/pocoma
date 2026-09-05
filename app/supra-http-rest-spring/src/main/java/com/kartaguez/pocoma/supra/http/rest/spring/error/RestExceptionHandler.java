package com.kartaguez.pocoma.supra.http.rest.spring.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.orchestrator.command.admission.ExpiredAuthenticatedPrincipalException;
import com.kartaguez.pocoma.orchestrator.command.admission.InvalidAuthenticatedExternalPrincipalException;
import com.kartaguez.pocoma.orchestrator.command.admission.UserNotProvisionedException;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ErrorResponse;
import com.kartaguez.pocoma.supra.http.rest.spring.filter.CommandRequestTooLargeException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class RestExceptionHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(RestExceptionHandler.class);

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<ErrorResponse> handleInvalidRequest(
			InvalidRequestException exception,
			HttpServletRequest request) {
		return error(exception.code(), exception.getMessage(), HttpStatus.BAD_REQUEST, request);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ResponseEntity<ErrorResponse> handleMissingHeader(
			MissingRequestHeaderException exception,
			HttpServletRequest request) {
		return error("MISSING_HEADER", exception.getMessage(), HttpStatus.BAD_REQUEST, request);
	}

	@ExceptionHandler({
			IllegalArgumentException.class,
			NullPointerException.class,
			MethodArgumentTypeMismatchException.class
	})
	ResponseEntity<ErrorResponse> handleBadRequest(Exception exception, HttpServletRequest request) {
		return error("INVALID_REQUEST", exception.getMessage(), HttpStatus.BAD_REQUEST, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleUnreadableMessage(
			HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		if (hasCause(exception, CommandRequestTooLargeException.class)) {
			return error("COMMAND_PAYLOAD_TOO_LARGE", "Command request exceeds the configured limit",
					HttpStatus.PAYLOAD_TOO_LARGE, request);
		}
		return error("INVALID_REQUEST", exception.getMessage(), HttpStatus.BAD_REQUEST, request);
	}

	@ExceptionHandler(BusinessEntityNotFoundException.class)
	ResponseEntity<ErrorResponse> handleNotFound(
			BusinessEntityNotFoundException exception,
			HttpServletRequest request) {
		return error(exception.entityCode(), exception.getMessage(), HttpStatus.NOT_FOUND, request);
	}

	@ExceptionHandler(VersionConflictException.class)
	ResponseEntity<ErrorResponse> handleConflict(
			VersionConflictException exception,
			HttpServletRequest request) {
		return error(exception.conflictCode(), exception.getMessage(), HttpStatus.CONFLICT, request);
	}

	@ExceptionHandler(BusinessRuleViolationException.class)
	ResponseEntity<ErrorResponse> handleForbidden(
			BusinessRuleViolationException exception,
			HttpServletRequest request) {
		return error(exception.ruleCode(), exception.getMessage(), HttpStatus.FORBIDDEN, request);
	}

	@ExceptionHandler(UserNotProvisionedException.class)
	ResponseEntity<ErrorResponse> handleUserNotProvisioned(
			UserNotProvisionedException exception,
			HttpServletRequest request) {
		return error("USER_NOT_PROVISIONED", exception.getMessage(), HttpStatus.FORBIDDEN, request);
	}

	@ExceptionHandler({
			InvalidAuthenticatedExternalPrincipalException.class,
			ExpiredAuthenticatedPrincipalException.class
	})
	ResponseEntity<ErrorResponse> handleInvalidAuthenticatedPrincipal(
			RuntimeException exception,
			HttpServletRequest request) {
		return error("INVALID_AUTHENTICATED_PRINCIPAL", exception.getMessage(), HttpStatus.UNAUTHORIZED, request);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
			HttpMediaTypeNotSupportedException exception,
			HttpServletRequest request) {
		return error("UNSUPPORTED_MEDIA_TYPE", exception.getMessage(), HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
		LOGGER.error("Unexpected HTTP request failure", exception);
		return error("INTERNAL_ERROR", "Unexpected error", HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	private static ResponseEntity<ErrorResponse> error(
			String code,
			String message,
			HttpStatus status,
			HttpServletRequest request) {
		return ResponseEntity.status(status)
				.body(new ErrorResponse(code, message, status.value(), request.getRequestURI()));
	}

	private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
		for (Throwable current = exception; current != null; current = current.getCause()) {
			if (type.isInstance(current)) return true;
		}
		return false;
	}
}
