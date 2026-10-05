package com.kartaguez.pocoma.engine.read.currentbinding;

import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBinding;

import java.util.Optional;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

public interface GetCurrentBindingUseCase {
	Optional<CurrentBinding> getAttached(ExternalIdentity identity);
}
