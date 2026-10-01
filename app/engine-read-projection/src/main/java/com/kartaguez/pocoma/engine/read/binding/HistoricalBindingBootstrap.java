package com.kartaguez.pocoma.engine.read.binding;

import java.time.Clock;
import java.util.Optional;

import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.engine.read.binding.HistoricalBindingSourcePort.ExternalIdentityCursor;

public final class HistoricalBindingBootstrap {
	private final HistoricalBindingSourcePort source;
	private final CurrentBindingProjectionPort projection;
	private final Clock clock;

	public HistoricalBindingBootstrap(HistoricalBindingSourcePort source,
			CurrentBindingProjectionPort projection, Clock clock) {
		this.source = java.util.Objects.requireNonNull(source);
		this.projection = java.util.Objects.requireNonNull(projection);
		this.clock = java.util.Objects.requireNonNull(clock);
	}

	/** Runs a bounded page and returns the next ephemeral cursor, empty when complete. */
	public Optional<ExternalIdentityCursor> runPage(Optional<ExternalIdentityCursor> cursor, int pageSize) {
		if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
		var page = source.findRevisionZeroPage(cursor, pageSize);
		for (var candidate : page) {
			projection.apply(new CurrentBinding(candidate.externalIdentity(), new BindingRevision(0),
					CurrentBindingStatus.ATTACHED, candidate.userId(), candidate.bindingId(), null, clock.instant()));
		}
		if (page.size() < pageSize) return Optional.empty();
		var last = page.getLast().externalIdentity();
		return Optional.of(new ExternalIdentityCursor(last.issuer(), last.subject()));
	}
}
