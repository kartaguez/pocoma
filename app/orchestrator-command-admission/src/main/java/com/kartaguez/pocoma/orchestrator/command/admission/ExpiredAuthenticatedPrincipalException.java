package com.kartaguez.pocoma.orchestrator.command.admission;

public final class ExpiredAuthenticatedPrincipalException extends RuntimeException {

	public ExpiredAuthenticatedPrincipalException() {
		super("Authenticated principal has expired before Command admission");
	}
}
