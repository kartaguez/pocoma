package com.kartaguez.pocoma.engine.service;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.policy.UpdatePotDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.engine.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.model.Versioned;
import com.kartaguez.pocoma.engine.port.in.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.usecase.UpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class UpdatePotDetailsService implements UpdatePotDetailsUseCase {

	private final PotContextPort loadUpdatePotDetailsContextPort;
	private final PotHeaderPort loadPotHeaderPort;
	private final PotGlobalVersionPort updatePotGlobalVersionPort;
	private final PotHeaderPort replacePotHeaderPort;
	private final EventPublisherPort publishPotDetailsUpdatedEventPort;
	private final UpdatePotDetailsAuthorizationPolicy updatePotDetailsAuthorizationPolicy;

	public UpdatePotDetailsService(
			PotContextPort loadUpdatePotDetailsContextPort,
			PotHeaderPort loadPotHeaderPort,
			PotGlobalVersionPort updatePotGlobalVersionPort,
			PotHeaderPort replacePotHeaderPort,
			EventPublisherPort publishPotDetailsUpdatedEventPort,
			UpdatePotDetailsAuthorizationPolicy updatePotDetailsAuthorizationPolicy) {
		this.loadUpdatePotDetailsContextPort = Objects.requireNonNull(
				loadUpdatePotDetailsContextPort,
				"loadUpdatePotDetailsContextPort must not be null");
		this.loadPotHeaderPort = Objects.requireNonNull(loadPotHeaderPort, "loadPotHeaderPort must not be null");
		this.updatePotGlobalVersionPort = Objects.requireNonNull(
				updatePotGlobalVersionPort,
				"updatePotGlobalVersionPort must not be null");
		this.replacePotHeaderPort = Objects.requireNonNull(replacePotHeaderPort, "replacePotHeaderPort must not be null");
		this.publishPotDetailsUpdatedEventPort = Objects.requireNonNull(
				publishPotDetailsUpdatedEventPort,
				"publishPotDetailsUpdatedEventPort must not be null");
		this.updatePotDetailsAuthorizationPolicy = Objects.requireNonNull(
				updatePotDetailsAuthorizationPolicy,
				"updatePotDetailsAuthorizationPolicy must not be null");
	}

	@Override
	public PotHeaderSnapshot updatePotDetails(UserContext userContext, UpdatePotDetailsCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");
		Objects.requireNonNull(userContext, "userContext must not be null");

		// 2. Load the precondition context needed by this modification use case.
		PotId potId = PotId.of(command.potId());
		UpdatePotDetailsContext context = Objects.requireNonNull(
				loadUpdatePotDetailsContextPort.loadUpdatePotDetailsContext(potId),
				"updatePotDetailsContext must not be null");
		PotGlobalVersion currentVersion = context.potGlobalVersion();

		// 3. Check state and optimistic version preconditions.
		context.assertUpdatePreconditions(command.expectedVersion());

		// 4. Check that the current user is allowed to update this pot.
		updatePotDetailsAuthorizationPolicy.assertCanUpdatePotDetails(userContext.userId(), context.creatorId());

		// 5. Load the full pot header active at the explicit working version.
		Versioned<PotHeader> currentVersionedPotHeader = Objects.requireNonNull(
				loadPotHeaderPort.loadActiveAtVersion(potId, currentVersion.version()),
				"potHeader must not be null");
		PotHeader currentPotHeader = currentVersionedPotHeader.value();

		// 6. Capture the previous immutable version and mutate the active domain aggregate.
		PotHeader previousPotHeaderValue = PotHeader.reconstitute(
				currentPotHeader.id(),
				currentPotHeader.label(),
				currentPotHeader.creatorId(),
				currentPotHeader.deleted());
		currentPotHeader.updateDetails(Label.of(command.label()));

		// 7. Increment the global version and build the replacement versioned state.
		long nextVersionNumber = currentVersion.version() + 1;
		PotGlobalVersion nextVersion = new PotGlobalVersion(potId, nextVersionNumber);
		Versioned<PotHeader> previousPotHeader = new Versioned<>(
				previousPotHeaderValue,
				currentVersionedPotHeader.startedAtVersion(),
				nextVersionNumber);
		Versioned<PotHeader> nextPotHeader = new Versioned<>(currentPotHeader, nextVersionNumber, null);

		// 8. Persist only if the explicit working version is still active.
		updatePotGlobalVersionPort.updateIfActive(currentVersion, nextVersion);
		replacePotHeaderPort.replace(previousPotHeader, nextPotHeader);

		// 9. Publish the business event for projection workers.
		publishPotDetailsUpdatedEventPort.publish(new PotDetailsUpdatedEvent(potId, nextVersionNumber));

		// 10. Return a versioned snapshot to the caller.
		return new PotHeaderSnapshot(
				currentPotHeader.id(),
				currentPotHeader.label(),
				currentPotHeader.creatorId(),
				currentPotHeader.deleted(),
				nextVersionNumber);
	}
}
