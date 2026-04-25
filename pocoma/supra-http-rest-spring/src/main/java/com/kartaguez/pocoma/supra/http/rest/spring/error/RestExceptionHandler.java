package com.kartaguez.pocoma.supra.http.rest.spring.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.supra.http.rest.spring.dto.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class RestExceptionHandler {

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
			HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class
	})
	ResponseEntity<ErrorResponse> handleBadRequest(Exception exception, HttpServletRequest request) {
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

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
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
}
