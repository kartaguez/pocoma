package com.kartaguez.pocoma.engine.admit.command;

public final class ExpiredAuthenticatedPrincipalException extends RuntimeException {

	public ExpiredAuthenticatedPrincipalException() {
		super("Authenticated principal has expired before Command admission");
	}
}
