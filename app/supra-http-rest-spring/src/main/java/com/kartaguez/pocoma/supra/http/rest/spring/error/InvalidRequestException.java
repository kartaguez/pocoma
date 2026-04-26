package com.kartaguez.pocoma.supra.http.rest.spring.error;

public final class InvalidRequestException extends RuntimeException {

	private final String code;

	public InvalidRequestException(String code, String message) {
		super(message);
		this.code = code;
	}

	public InvalidRequestException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
