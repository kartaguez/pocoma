package com.kartaguez.pocoma.engine.service.command;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.policy.DeletePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.context.DeletePotContext;
import com.kartaguez.pocoma.engine.event.PotDeletedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeletePotUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class DeletePotService implements DeletePotUseCase {

	private final PotContextPort loadDeletePotContextPort;
	private final PotHeaderPort loadPotHeaderPort;
	private final PotGlobalVersionPort updatePotGlobalVersionPort;
	private final PotHeaderPort replacePotHeaderPort;
	private final EventPublisherPort publishPotDeletedEventPort;
	private final DeletePotAuthorizationPolicy deletePotAuthorizationPolicy;

	DeletePotService(
			PotContextPort loadDeletePotContextPort,
			PotHeaderPort loadPotHeaderPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			PotHeaderPort replacePotHeaderPort,
			EventPublisherPort publishPotDeletedEventPort,
			DeletePotAuthorizationPolicy deletePotAuthorizationPolicy) {
		this.loadDeletePotContextPort = Objects.requireNonNull(
				loadDeletePotContextPort,
				"loadDeletePotContextPort must not be null");
		this.loadPotHeaderPort = Objects.requireNonNull(loadPotHeaderPort, "loadPotHeaderPort must not be null");
		this.updatePotGlobalVersionPort = Objects.requireNonNull(
				updatePotGlobalVersionPort,
				"updatePotGlobalVersionPort must not be null");
		this.replacePotHeaderPort = Objects.requireNonNull(replacePotHeaderPort, "replacePotHeaderPort must not be null");
		this.publishPotDeletedEventPort = Objects.requireNonNull(
				publishPotDeletedEventPort,
				"publishPotDeletedEventPort must not be null");
		this.deletePotAuthorizationPolicy = Objects.requireNonNull(
				deletePotAuthorizationPolicy,
				"deletePotAuthorizationPolicy must not be null");
	}

	@Override
	public PotHeaderSnapshot deletePot(UserContext userContext, DeletePotCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(userContext, "userContext must not be null");

		// 2. Load the precondition context needed by this modification use case.
		PotId potId = PotId.of(command.potId());
		DeletePotContext context = Objects.requireNonNull(
				loadDeletePotContextPort.loadDeletePotContext(potId),
				"deletePotContext must not be null");
		PotGlobalVersion currentVersion = context.potGlobalVersion();

		// 3. Check state and optimistic version preconditions.
		context.assertDeletePreconditions(command.expectedVersion());

		// 4. Check that the current user is allowed to delete this pot.
		deletePotAuthorizationPolicy.assertCanDeletePot(userContext.userId(), context.creatorId());

		// 5. Load the full pot header active at the explicit working version.
		PotHeader currentPotHeader = Objects.requireNonNull(
				loadPotHeaderPort.loadActiveAtVersion(potId, currentVersion.version()),
				"potHeader must not be null");

		// 6. Mutate the active domain aggregate.
		currentPotHeader.markAsDeleted();

		// 7. Increment the global version and persist the new aggregate state.
		long nextVersionNumber = currentVersion.version() + 1;
		PotGlobalVersion nextVersion = new PotGlobalVersion(potId, nextVersionNumber);

		// 8. Persist only if the explicit working version is still active.
		updatePotGlobalVersionPort.updateIfActive(currentVersion, nextVersion);
		replacePotHeaderPort.save(currentPotHeader, currentVersion, nextVersion);

		// 9. Publish the business event for projection workers.
		publishPotDeletedEventPort.publish(new PotDeletedEvent(potId, nextVersionNumber));

		// 10. Return a versioned snapshot to the caller.
		return new PotHeaderSnapshot(
				currentPotHeader.id(),
				currentPotHeader.label(),
				currentPotHeader.creatorId(),
				currentPotHeader.deleted(),
				nextVersionNumber);
	}
}
