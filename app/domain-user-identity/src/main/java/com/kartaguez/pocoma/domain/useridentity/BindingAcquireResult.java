package com.kartaguez.pocoma.domain.useridentity;

/** Result of atomically acquiring one external identity occurrence. */
public record BindingAcquireResult(Status status, BindingId bindingId) {
	public enum Status { ACQUIRED, CONFLICT }

	public BindingAcquireResult {
		if (status == null || (status == Status.ACQUIRED) != (bindingId != null)) {
			throw new IllegalArgumentException("Only a successful acquisition has a BindingId");
		}
	}

	public static BindingAcquireResult acquired(BindingId bindingId) {
		return new BindingAcquireResult(Status.ACQUIRED, bindingId);
	}

	public static BindingAcquireResult conflict() {
		return new BindingAcquireResult(Status.CONFLICT, null);
	}
}
