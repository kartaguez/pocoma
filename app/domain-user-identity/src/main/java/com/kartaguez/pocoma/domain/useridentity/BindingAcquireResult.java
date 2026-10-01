package com.kartaguez.pocoma.domain.useridentity;

/** Result of atomically acquiring one external identity occurrence. */
public enum BindingAcquireResult {
	ACQUIRED,
	CONFLICT
}
