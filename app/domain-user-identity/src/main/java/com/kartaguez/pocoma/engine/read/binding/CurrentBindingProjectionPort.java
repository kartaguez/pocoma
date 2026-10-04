package com.kartaguez.pocoma.engine.read.binding;

import java.util.Optional;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

public interface CurrentBindingProjectionPort {
	CurrentBindingApplyResult apply(CurrentBinding binding);
	Optional<CurrentBinding> find(ExternalIdentity identity);
}
