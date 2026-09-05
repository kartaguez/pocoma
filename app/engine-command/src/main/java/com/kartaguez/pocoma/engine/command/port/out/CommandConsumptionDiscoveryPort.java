package com.kartaguez.pocoma.engine.command.port.out;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.engine.command.discovery.CommandConsumptionCandidate;
import com.kartaguez.pocoma.engine.command.discovery.CommandDiscoveryCursor;

/** Best-effort selection of Commands that appear eligible now. Acquisition remains authoritative. */
public interface CommandConsumptionDiscoveryPort {

	Optional<CommandConsumptionCandidate> findNextEligibleCandidate(
			Instant now, Optional<CommandDiscoveryCursor> afterExclusive);
}
