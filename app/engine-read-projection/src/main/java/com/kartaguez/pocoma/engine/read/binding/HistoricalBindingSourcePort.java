package com.kartaguez.pocoma.engine.read.binding;

import java.util.List;
import java.util.Optional;

public interface HistoricalBindingSourcePort {
	List<HistoricalBindingCandidate> findRevisionZeroPage(Optional<ExternalIdentityCursor> afterExclusive, int limit);

	record ExternalIdentityCursor(String issuer, String subject) {}
}
