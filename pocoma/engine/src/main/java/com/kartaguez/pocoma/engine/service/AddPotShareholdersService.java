package com.kartaguez.pocoma.engine.service;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.policy.AddPotShareholdersAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.context.AddPotShareholdersContext;
import com.kartaguez.pocoma.engine.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.model.Versioned;
import com.kartaguez.pocoma.engine.port.in.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.usecase.AddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class AddPotShareholdersService implements AddPotShareholdersUseCase {

	private final PotContextPort loadAddPotShareholdersContextPort;
	private final PotShareholdersPort loadPotShareholdersPort;
	private final PotGlobalVersionPort updatePotGlobalVersionPort;
	private final PotShareholdersPort replacePotShareholdersPort;
	private final EventPublisherPort publishPotShareholdersAddedEventPort;
	private final AddPotShareholdersAuthorizationPolicy addPotShareholdersAuthorizationPolicy;

	public AddPotShareholdersService(
			PotContextPort loadAddPotShareholdersContextPort,
			PotShareholdersPort loadPotShareholdersPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			PotShareholdersPort replacePotShareholdersPort,
			EventPublisherPort publishPotShareholdersAddedEventPort,
			AddPotShareholdersAuthorizationPolicy addPotShareholdersAuthorizationPolicy) {
		this.loadAddPotShareholdersContextPort = Objects.requireNonNull(
				loadAddPotShareholdersContextPort,
				"loadAddPotShareholdersContextPort must not be null");
		this.loadPotShareholdersPort = Objects.requireNonNull(
				loadPotShareholdersPort,
				"loadPotShareholdersPort must not be null");
		this.updatePotGlobalVersionPort = Objects.requireNonNull(
				updatePotGlobalVersionPort,
				"updatePotGlobalVersionPort must not be null");
		this.replacePotShareholdersPort = Objects.requireNonNull(
				replacePotShareholdersPort,
				"replacePotShareholdersPort must not be null");
		this.publishPotShareholdersAddedEventPort = Objects.requireNonNull(
				publishPotShareholdersAddedEventPort,
				"publishPotShareholdersAddedEventPort must not be null");
		this.addPotShareholdersAuthorizationPolicy = Objects.requireNonNull(
				addPotShareholdersAuthorizationPolicy,
				"addPotShareholdersAuthorizationPolicy must not be null");
	}

	@Override
	public PotShareholdersSnapshot addPotShareholders(UserContext userContext, AddPotShareholdersCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(userContext, "userContext must not be null");

		// 2. Load the precondition context needed by this modification use case.
		PotId potId = PotId.of(command.potId());
		AddPotShareholdersContext context = Objects.requireNonNull(
				loadAddPotShareholdersContextPort.loadAddPotShareholdersContext(potId),
				"addPotShareholdersContext must not be null");
		PotGlobalVersion currentVersion = context.potGlobalVersion();

		// 3. Check state and optimistic version preconditions.
		context.assertAddPreconditions(command.expectedVersion());

		// 4. Check that the current user is allowed to add shareholders to this pot.
		addPotShareholdersAuthorizationPolicy.assertCanAddPotShareholders(userContext.userId(), context.creatorId());

		// 5. Load the full pot shareholders aggregate active at the explicit working version.
		Versioned<PotShareholders> currentVersionedPotShareholders = Objects.requireNonNull(
				loadPotShareholdersPort.loadActiveAtVersion(potId, currentVersion.version()),
				"potShareholders must not be null");
		PotShareholders currentPotShareholders = currentVersionedPotShareholders.value();

		// 6. Capture the previous immutable version and mutate the active domain aggregate.
		PotShareholders previousPotShareholdersValue = PotShareholders.reconstitute(
				currentPotShareholders.potId(),
				Set.copyOf(currentPotShareholders.shareholders().values()));
		command.shareholders().forEach(shareholder -> currentPotShareholders.addShareholder(
				Name.of(shareholder.name()),
				Weight.of(Fraction.of(shareholder.weightNumerator(), shareholder.weightDenominator())),
				null));

		// 7. Increment the global version and build the replacement versioned state.
		long nextVersionNumber = currentVersion.version() + 1;
		PotGlobalVersion nextVersion = new PotGlobalVersion(potId, nextVersionNumber);
		Versioned<PotShareholders> previousPotShareholders = new Versioned<>(
				previousPotShareholdersValue,
				currentVersionedPotShareholders.startedAtVersion(),
				nextVersionNumber);
		Versioned<PotShareholders> nextPotShareholders =
				new Versioned<>(currentPotShareholders, nextVersionNumber, null);

		// 8. Persist only if the explicit working version is still active.
		updatePotGlobalVersionPort.updateIfActive(currentVersion, nextVersion);
		replacePotShareholdersPort.replace(previousPotShareholders, nextPotShareholders);

		// 9. Publish the business event for projection workers.
		publishPotShareholdersAddedEventPort.publish(new PotShareholdersAddedEvent(
				potId,
				currentPotShareholders.addedShareholderIds(),
				nextVersionNumber));

		// 10. Return a versioned snapshot to the caller.
		return new PotShareholdersSnapshot(
				currentPotShareholders.potId(),
				currentPotShareholders.shareholders().values().stream().collect(Collectors.toSet()),
				nextVersionNumber);
	}
}
