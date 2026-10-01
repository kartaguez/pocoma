package com.kartaguez.pocoma.engine.read.binding;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

public final class GetCurrentBindingService implements GetCurrentBindingUseCase {
	private final CurrentBindingProjectionPort bindings;

	public GetCurrentBindingService(CurrentBindingProjectionPort bindings) {
		this.bindings = requireNonNull(bindings, "bindings must not be null");
	}

	@Override
	public Optional<CurrentBinding> getAttached(ExternalIdentity identity) {
		requireNonNull(identity, "identity must not be null");
		return bindings.find(identity).filter(binding -> binding.status() == CurrentBindingStatus.ATTACHED);
	}
}
