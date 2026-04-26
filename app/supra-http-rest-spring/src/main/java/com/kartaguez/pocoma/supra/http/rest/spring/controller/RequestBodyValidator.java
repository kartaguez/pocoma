package com.kartaguez.pocoma.supra.http.rest.spring.controller;

import java.util.List;

import com.kartaguez.pocoma.supra.http.rest.spring.error.InvalidRequestException;

final class RequestBodyValidator {

	private RequestBodyValidator() {
	}

	static <T> T requireBody(T body) {
		if (body == null) {
			throw new InvalidRequestException("INVALID_REQUEST", "Request body is required");
		}
		return body;
	}

	static <T> List<T> requireList(List<T> value, String name) {
		if (value == null) {
			throw new InvalidRequestException("INVALID_REQUEST", name + " must not be null");
		}
		return value;
	}
}
