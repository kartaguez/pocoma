package com.kartaguez.pocoma.orchestrator.command.admission;

public final class InvalidAuthenticatedExternalPrincipalException extends RuntimeException {

	public InvalidAuthenticatedExternalPrincipalException(String message) {
		super(message);
	}

	public InvalidAuthenticatedExternalPrincipalException(String message, Throwable cause) {
		super(message, cause);
	}
}
