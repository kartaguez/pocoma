package com.kartaguez.pocoma.engine.service.command;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.policy.UpdatePotShareholdersWeightsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersWeightsContext;
import com.kartaguez.pocoma.engine.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersWeightsUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class UpdatePotShareholdersWeightsService implements UpdatePotShareholdersWeightsUseCase {

	private final PotContextPort loadUpdatePotShareholdersWeightsContextPort;
	private final PotShareholdersPort loadPotShareholdersPort;
	private final PotGlobalVersionPort updatePotGlobalVersionPort;
	private final PotShareholdersPort replacePotShareholdersPort;
	private final EventPublisherPort publishPotShareholdersWeightsUpdatedEventPort;
	private final UpdatePotShareholdersWeightsAuthorizationPolicy updatePotShareholdersWeightsAuthorizationPolicy;

	UpdatePotShareholdersWeightsService(
			PotContextPort loadUpdatePotShareholdersWeightsContextPort,
			PotShareholdersPort loadPotShareholdersPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			PotShareholdersPort replacePotShareholdersPort,
			EventPublisherPort publishPotShareholdersWeightsUpdatedEventPort,
			UpdatePotShareholdersWeightsAuthorizationPolicy updatePotShareholdersWeightsAuthorizationPolicy) {
		this.loadUpdatePotShareholdersWeightsContextPort = Objects.requireNonNull(
				loadUpdatePotShareholdersWeightsContextPort,
				"loadUpdatePotShareholdersWeightsContextPort must not be null");
		this.loadPotShareholdersPort = Objects.requireNonNull(
				loadPotShareholdersPort,
				"loadPotShareholdersPort must not be null");
		this.updatePotGlobalVersionPort = Objects.requireNonNull(
				updatePotGlobalVersionPort,
				"updatePotGlobalVersionPort must not be null");
		this.replacePotShareholdersPort = Objects.requireNonNull(
				replacePotShareholdersPort,
				"replacePotShareholdersPort must not be null");
		this.publishPotShareholdersWeightsUpdatedEventPort = Objects.requireNonNull(
				publishPotShareholdersWeightsUpdatedEventPort,
				"publishPotShareholdersWeightsUpdatedEventPort must not be null");
		this.updatePotShareholdersWeightsAuthorizationPolicy = Objects.requireNonNull(
				updatePotShareholdersWeightsAuthorizationPolicy,
				"updatePotShareholdersWeightsAuthorizationPolicy must not be null");
	}

	@Override
	public PotShareholdersSnapshot updatePotShareholdersWeights(
			UserContext userContext,
			UpdatePotShareholdersWeightsCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(userContext, "userContext must not be null");

		// 2. Load the precondition context needed by this modification use case.
		PotId potId = PotId.of(command.potId());
		Set<ShareholderId> updatedShareholderIds = command.shareholders().stream()
				.map(shareholder -> ShareholderId.of(shareholder.shareholderId()))
				.collect(Collectors.toSet());
		UpdatePotShareholdersWeightsContext context = Objects.requireNonNull(
				loadUpdatePotShareholdersWeightsContextPort.loadUpdatePotShareholdersWeightsContext(potId),
				"updatePotShareholdersWeightsContext must not be null");
		PotGlobalVersion currentVersion = context.potGlobalVersion();

		// 3. Check state and optimistic version preconditions.
		context.assertUpdatePreconditions(command.expectedVersion(), updatedShareholderIds);

		// 4. Check that the current user is allowed to update shareholders weights.
		updatePotShareholdersWeightsAuthorizationPolicy.assertCanUpdatePotShareholdersWeights(
				userContext.userId(),
				context.creatorId());

		// 5. Load the full pot shareholders aggregate active at the explicit working version.
		PotShareholders currentPotShareholders = Objects.requireNonNull(
				loadPotShareholdersPort.loadActiveAtVersion(potId, currentVersion.version()),
				"potShareholders must not be null");

		// 6. Mutate the active domain aggregate.
		command.shareholders().forEach(shareholder -> currentPotShareholders.updateShareholderWeight(
				ShareholderId.of(shareholder.shareholderId()),
				Weight.of(Fraction.of(shareholder.weightNumerator(), shareholder.weightDenominator()))));

		// 7. Increment the global version and persist the new aggregate state.
		long nextVersionNumber = currentVersion.version() + 1;
		PotGlobalVersion nextVersion = new PotGlobalVersion(potId, nextVersionNumber);

		// 8. Persist only if the explicit working version is still active.
		updatePotGlobalVersionPort.updateIfActive(currentVersion, nextVersion);
		replacePotShareholdersPort.save(currentPotShareholders, currentVersion, nextVersion);

		// 9. Publish the business event for projection workers.
		publishPotShareholdersWeightsUpdatedEventPort.publish(new PotShareholdersWeightsUpdatedEvent(
				potId,
				currentPotShareholders.updatedShareholderIds(),
				nextVersionNumber));

		// 10. Return a versioned snapshot to the caller.
		return new PotShareholdersSnapshot(
				currentPotShareholders.potId(),
				currentPotShareholders.shareholders().values().stream().collect(Collectors.toSet()),
				nextVersionNumber);
	}
}
