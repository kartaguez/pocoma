package com.kartaguez.pocoma.engine.read.projection;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record PotUserIndexPage(List<PotUserIndexEntry> entries, Optional<PotListCursor> nextCursor) {
	public PotUserIndexPage {
		entries = List.copyOf(requireNonNull(entries, "entries must not be null"));
		nextCursor = requireNonNull(nextCursor, "nextCursor must not be null");
	}
}
