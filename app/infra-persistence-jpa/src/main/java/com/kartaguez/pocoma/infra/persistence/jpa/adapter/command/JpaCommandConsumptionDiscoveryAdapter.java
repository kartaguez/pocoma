package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.engine.command.discovery.CommandConsumptionCandidate;
import com.kartaguez.pocoma.engine.command.discovery.CommandDiscoveryCursor;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.port.out.CommandConsumptionDiscoveryPort;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.JpaCommandConsumptionDiscoveryRepository;

@Component
public class JpaCommandConsumptionDiscoveryAdapter implements CommandConsumptionDiscoveryPort {
	private final JpaCommandConsumptionDiscoveryRepository repository;

	public JpaCommandConsumptionDiscoveryAdapter(JpaCommandConsumptionDiscoveryRepository repository) {
		this.repository = requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CommandConsumptionCandidate> findNextEligibleCandidate(
			Instant now, Optional<CommandDiscoveryCursor> afterExclusive) {
		requireNonNull(now, "now must not be null");
		requireNonNull(afterExclusive, "afterExclusive must not be null");
		return repository.findNextEligible(now, afterExclusive)
				.map(row -> new CommandConsumptionCandidate(
						new CommandId(row.commandId()), row.submittedAt()));
	}
}
