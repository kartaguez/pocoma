package com.kartaguez.pocoma.engine.read.binding;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

public record HistoricalBindingCandidate(ExternalIdentity externalIdentity, PocomaUserId userId,
		BindingId bindingId) {}
