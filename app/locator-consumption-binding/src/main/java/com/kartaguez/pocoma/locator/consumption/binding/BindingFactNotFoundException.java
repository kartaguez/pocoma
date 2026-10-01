package com.kartaguez.pocoma.locator.consumption.binding;

import java.util.UUID;

public final class BindingFactNotFoundException extends RuntimeException {
	public BindingFactNotFoundException(UUID eventId) { super("Binding fact not found: " + eventId); }
}
