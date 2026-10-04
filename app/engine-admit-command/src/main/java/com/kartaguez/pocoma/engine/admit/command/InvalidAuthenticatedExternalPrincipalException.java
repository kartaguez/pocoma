package com.kartaguez.pocoma.engine.admit.command;

public final class InvalidAuthenticatedExternalPrincipalException extends RuntimeException {

	public InvalidAuthenticatedExternalPrincipalException(String message) {
		super(message);
	}

	public InvalidAuthenticatedExternalPrincipalException(String message, Throwable cause) {
		super(message, cause);
	}
}
