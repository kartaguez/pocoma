package com.kartaguez.pocoma.engine.service.command;

import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTarget;
import com.kartaguez.pocoma.domain.pot.authorization.PotAction;
import com.kartaguez.pocoma.domain.pot.authorization.PotAuthorizationRelations;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.context.UpdatePotShareholdersWeightsContext;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.snapshot.PotShareholdersSnapshot;
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
	private final PotAuthorizationGuard authorizationGuard;

	UpdatePotShareholdersWeightsService(
			PotContextPort loadUpdatePotShareholdersWeightsContextPort,
			PotShareholdersPort loadPotShareholdersPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			PotShareholdersPort replacePotShareholdersPort,
			EventPublisherPort publishPotShareholdersWeightsUpdatedEventPort,
			PotAuthorizationGuard authorizationGuard) {
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
		this.authorizationGuard = Objects.requireNonNull(authorizationGuard, "authorizationGuard must not be null");
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
		PotAuthorizationRelations authorizationRelations = new PotAuthorizationRelations(
				potId, context.creatorId(), Map.of());
		for (ShareholderId shareholderId : updatedShareholderIds) {
			authorizationGuard.assertAuthorized(
					userContext.userId(),
					userContext.permissions(),
					authorizationRelations,
					AuthorizationTarget.existing(shareholderId),
					PotAction.UPDATE_SHAREHOLDER_WEIGHTS,
					"POT_SHAREHOLDERS_WEIGHTS_UPDATE_FORBIDDEN",
					"Only the pot creator can update shareholder weights");
		}

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
