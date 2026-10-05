package com.kartaguez.pocoma.engine.read.currentbinding;

import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBinding;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBindingStatus;
import com.kartaguez.pocoma.engine.read.currentbinding.port.CurrentBindingReadPort;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

public final class GetCurrentBindingService implements GetCurrentBindingUseCase {
	private final CurrentBindingReadPort bindings;

	public GetCurrentBindingService(CurrentBindingReadPort bindings) {
		this.bindings = requireNonNull(bindings, "bindings must not be null");
	}

	@Override
	public Optional<CurrentBinding> getAttached(ExternalIdentity identity) {
		requireNonNull(identity, "identity must not be null");
		return bindings.find(identity).filter(binding -> binding.status() == CurrentBindingStatus.ATTACHED);
	}
}
