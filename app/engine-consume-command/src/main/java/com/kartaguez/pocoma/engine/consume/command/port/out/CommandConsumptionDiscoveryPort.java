package com.kartaguez.pocoma.engine.consume.command.port.out;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.engine.consume.command.discovery.CommandConsumptionCandidate;
import com.kartaguez.pocoma.engine.consume.command.discovery.CommandDiscoveryCursor;

/** Best-effort selection of Commands that appear eligible now. Acquisition remains authoritative. */
public interface CommandConsumptionDiscoveryPort {

	Optional<CommandConsumptionCandidate> findNextEligibleCandidate(
			Instant now, Optional<CommandDiscoveryCursor> afterExclusive);
}
