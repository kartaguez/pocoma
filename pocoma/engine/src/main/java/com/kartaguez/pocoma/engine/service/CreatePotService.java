package com.kartaguez.pocoma.engine.service;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.created.PotCreated;
import com.kartaguez.pocoma.domain.factory.PotFactory;
import com.kartaguez.pocoma.domain.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class CreatePotService implements CreatePotUseCase {

	private static final long INITIAL_VERSION = 1L;

	private final PotGlobalVersionPort savePotGlobalVersionPort;
	private final PotHeaderPort savePotHeaderPort;
	private final EventPublisherPort publishPotCreatedEventPort;
	private final CreatePotAuthorizationPolicy createPotAuthorizationPolicy;

	public CreatePotService(
			PotGlobalVersionPort savePotGlobalVersionPort,
			PotHeaderPort savePotHeaderPort,
			EventPublisherPort publishPotCreatedEventPort,
			CreatePotAuthorizationPolicy createPotAuthorizationPolicy) {
		this.savePotGlobalVersionPort = Objects.requireNonNull(
				savePotGlobalVersionPort,
				"savePotGlobalVersionPort must not be null");
		this.savePotHeaderPort = Objects.requireNonNull(savePotHeaderPort, "savePotHeaderPort must not be null");
		this.publishPotCreatedEventPort = Objects.requireNonNull(
				publishPotCreatedEventPort,
				"publishPotCreatedEventPort must not be null");
		this.createPotAuthorizationPolicy = Objects.requireNonNull(
				createPotAuthorizationPolicy,
				"createPotAuthorizationPolicy must not be null");
	}

	@Override
	public PotHeaderSnapshot createPot(UserContext userContext, CreatePotCommand command) {
		// 1. Validate the incoming application command.
		Objects.requireNonNull(command, "command must not be null");

		// 2. Check that the current user is allowed to create a pot.
		Objects.requireNonNull(userContext, "userContext must not be null");
		createPotAuthorizationPolicy.assertCanCreatePot(userContext.userId());

		// 3. Convert simple input data into domain value objects and create the pot.
		PotCreated potCreated = PotFactory.createPot(
				Label.of(command.label()),
				UserId.of(command.creatorId()));

		// 4. Initialize the pot global version.
		PotGlobalVersion potGlobalVersion = new PotGlobalVersion(potCreated.id(), INITIAL_VERSION);

		// 5. Reconstitute the first active domain state.
		PotHeader potHeader = PotHeader.reconstitute(
				potCreated.id(),
				potCreated.label(),
				potCreated.creatorId(),
				false);

		// 6. Persist the version clock and the versioned domain state through output ports.
		savePotGlobalVersionPort.save(potGlobalVersion);
		savePotHeaderPort.saveNew(potHeader, INITIAL_VERSION);

		// 7. Publish the business event for projection workers.
		publishPotCreatedEventPort.publish(new PotCreatedEvent(potCreated.id(), INITIAL_VERSION));

		// 8. Return a versioned snapshot to the caller.
		return new PotHeaderSnapshot(
				potHeader.id(),
				potHeader.label(),
				potHeader.creatorId(),
				potHeader.deleted(),
				INITIAL_VERSION);
	}
}
