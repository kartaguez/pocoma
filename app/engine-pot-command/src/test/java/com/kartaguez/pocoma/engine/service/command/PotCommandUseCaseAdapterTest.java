package com.kartaguez.pocoma.engine.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.entity.Shareholder;
import com.kartaguez.pocoma.domain.pot.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.policy.AddPotShareholdersAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.CreateExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.DeleteExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.DeletePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdateExpenseDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdateExpenseSharesAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdatePotDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdatePotShareholdersDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdatePotShareholdersWeightsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.command.dispatch.CommandDispatcher;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCase;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.Permission;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.context.AddPotShareholdersContext;
import com.kartaguez.pocoma.engine.context.UpdatePotDetailsContext;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.security.UserContext;

class PotCommandUseCaseAdapterTest {

	private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final PotId POT_ID = PotId.of(UUID.fromString("20000000-0000-0000-0000-000000000001"));

	@Test
	void mapsOnlyCanonicalPotPermissionsAndPreservesTheUserIdentity() {
		AtomicReference<UserContext> received = new AtomicReference<>();
		TestAdapter adapter = new TestAdapter((invocation, userContext) -> received.set(userContext));
		Set<Permission> permissions = Set.of(
				new Permission("POT", "CREATE"),
				new Permission("POT.DETAILS", "UPDATE"),
				new Permission("EXPENSE.SHARES", "UPDATE"),
				new Permission("QUERY", "READ"),
				new Permission("pot", "delete"));

		adapter.execute(authorization(permissions), command());

		assertEquals(UserId.of(USER_ID), received.get().userId());
		assertEquals(Set.of(
				new Scope(Scope.Resource.POT, null, Scope.Action.CREATE),
				new Scope(Scope.Resource.POT, Scope.SubResource.DETAILS, Scope.Action.UPDATE),
				new Scope(Scope.Resource.EXPENSE, Scope.SubResource.SHARES, Scope.Action.UPDATE)),
				received.get().scopes());
	}

	@Test
	void returnsTypedEventsAndThePotVersionActuallyRead() {
		PotDetailsUpdatedEvent event = new PotDetailsUpdatedEvent(POT_ID, 8);
		TestAdapter adapter = new TestAdapter((invocation, ignored) -> {
			PotContextPort contextPort = invocation.recording(new PotContextPort() {
				@Override public UpdatePotDetailsContext loadUpdatePotDetailsContext(PotId potId) {
					return new UpdatePotDetailsContext(new PotGlobalVersion(potId, 7), false, UserId.of(USER_ID));
				}
			});
			contextPort.loadUpdatePotDetailsContext(POT_ID);
			invocation.publish(event);
		});

		CommandUseCaseResult.Succeeded result = assertInstanceOf(CommandUseCaseResult.Succeeded.class,
				adapter.execute(authorization(Set.of()), command()));

		assertEquals(List.of(new com.kartaguez.pocoma.engine.command.model.CommandExecutionInput(
				"POT", POT_ID.value().toString(), 7)), result.inputs());
		assertEquals(1, result.events().size());
		assertSame(event, result.events().getFirst());
	}

	@Test
	void convertsOnlyExpectedBusinessExceptionsToRejections() {
		assertRejected("RULE", new BusinessRuleViolationException("RULE", "rejected"));
		assertRejected("POT_VERSION_CONFLICT", new VersionConflictException("conflict"));
		assertRejected("POT_HEADER", new BusinessEntityNotFoundException("POT_HEADER", "missing"));

		TechnicalException technical = new TechnicalException();
		TestAdapter adapter = new TestAdapter((invocation, ignored) -> { throw technical; });
		assertSame(technical, assertThrows(TechnicalException.class,
				() -> adapter.execute(authorization(Set.of()), command())));
	}

	@Test
	void preservesTheObservedPotVersionWhenAConflictIsRejected() {
		TestAdapter adapter = new TestAdapter((invocation, ignored) -> {
			PotContextPort contextPort = invocation.recording(new PotContextPort() {
				@Override public UpdatePotDetailsContext loadUpdatePotDetailsContext(PotId potId) {
					return new UpdatePotDetailsContext(new PotGlobalVersion(potId, 11), false, UserId.of(USER_ID));
				}
			});
			contextPort.loadUpdatePotDetailsContext(POT_ID);
			throw new VersionConflictException("conflict");
		});

		CommandUseCaseResult.Rejected result = assertInstanceOf(CommandUseCaseResult.Rejected.class,
				adapter.execute(authorization(Set.of()), command()));

		assertEquals("POT_VERSION_CONFLICT", result.reason().code());
		assertEquals(List.of(new com.kartaguez.pocoma.engine.command.model.CommandExecutionInput(
				"POT", POT_ID.value().toString(), 11)), result.inputs());
	}

	@Test
	void refusesToTurnAnExceptionAfterEventProductionIntoARejection() {
		TestAdapter adapter = new TestAdapter((invocation, ignored) -> {
			invocation.publish(new PotDetailsUpdatedEvent(POT_ID, 2));
			throw new BusinessRuleViolationException("TOO_LATE", "too late");
		});

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> adapter.execute(authorization(Set.of()), command()));
		assertInstanceOf(BusinessRuleViolationException.class, exception.getCause());
	}

	@Test
	void acceptsAValidSuccessWithoutEvents() {
		CommandUseCaseResult.Succeeded result = assertInstanceOf(CommandUseCaseResult.Succeeded.class,
				new TestAdapter((invocation, ignored) -> { }).execute(authorization(Set.of()), command()));
		assertEquals(List.of(), result.inputs());
		assertEquals(List.of(), result.events());
	}

	@Test
	void createPotRejectsAConflictingCreatorBeforeCallingAnyPort() {
		AtomicBoolean called = new AtomicBoolean();
		PotGlobalVersionPort versions = new PotGlobalVersionPort() {
			@Override public void save(PotGlobalVersion version) { called.set(true); }
		};
		CreatePotCommandUseCaseAdapter adapter = new CreatePotCommandUseCaseAdapter(
				versions, new PotHeaderPort() { }, new CreatePotAuthorizationPolicy());

		CommandUseCaseResult.Rejected result = assertInstanceOf(CommandUseCaseResult.Rejected.class,
				adapter.execute(authorization(Set.of(new Permission("POT", "CREATE"))),
						new CreatePotCommand("Trip", UUID.randomUUID())));

		assertEquals("NOT_AUTHORIZED_ON_RESOURCE", result.reason().code());
		assertEquals(List.of(), result.inputs());
		assertFalse(called.get());
	}

	@Test
	void createPotUsesTheExistingServiceAndReturnsItsTypedEvent() {
		AtomicReference<PotGlobalVersion> savedVersion = new AtomicReference<>();
		AtomicReference<com.kartaguez.pocoma.domain.pot.aggregate.PotHeader> savedHeader = new AtomicReference<>();
		CreatePotCommandUseCaseAdapter adapter = new CreatePotCommandUseCaseAdapter(
				new PotGlobalVersionPort() {
					@Override public void save(PotGlobalVersion version) { savedVersion.set(version); }
				},
				new PotHeaderPort() {
					@Override public void saveNew(com.kartaguez.pocoma.domain.pot.aggregate.PotHeader header,
							long version) { savedHeader.set(header); }
				},
				new CreatePotAuthorizationPolicy());

		CommandUseCaseResult.Succeeded result = assertInstanceOf(CommandUseCaseResult.Succeeded.class,
				adapter.execute(authorization(Set.of(new Permission("POT", "CREATE"))), command()));

		assertEquals(1, savedVersion.get().version());
		assertEquals("Trip", savedHeader.get().label().value());
		assertInstanceOf(com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent.class, result.events().getFirst());
		assertEquals(List.of(), result.inputs());
	}

	@Test
	void addShareholdersCapturesGeneratedIdsAndObservedPotVersion() {
		UserId creator = UserId.of(USER_ID);
		Shareholder existing = Shareholder.reconstitute(ShareholderId.of(UUID.randomUUID()), POT_ID,
				Name.of("Existing"), Weight.of(Fraction.ONE), null, false);
		PotShareholders aggregate = PotShareholders.reconstitute(POT_ID, Set.of(existing));
		PotShareholdersPort shareholders = new PotShareholdersPort() {
			@Override public PotShareholders loadActiveAtVersion(PotId potId, long version) { return aggregate; }
			@Override public void save(PotShareholders value, PotGlobalVersion current, PotGlobalVersion next) { }
		};
		AddPotShareholdersCommandUseCaseAdapter adapter = new AddPotShareholdersCommandUseCaseAdapter(
				new PotContextPort() {
					@Override public AddPotShareholdersContext loadAddPotShareholdersContext(PotId potId) {
						return new AddPotShareholdersContext(new PotGlobalVersion(potId, 3), false, creator);
					}
				}, shareholders, new PotGlobalVersionPort() {
					@Override public void updateIfActive(PotGlobalVersion current, PotGlobalVersion next) { }
				}, new AddPotShareholdersAuthorizationPolicy());

		CommandUseCaseResult.Succeeded result = assertInstanceOf(CommandUseCaseResult.Succeeded.class,
				adapter.execute(authorization(Set.of(new Permission("SHAREHOLDER", "CREATE"))),
						new AddPotShareholdersCommand(POT_ID.value(),
								Set.of(new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 2)), 3)));

		PotShareholdersAddedEvent event = assertInstanceOf(PotShareholdersAddedEvent.class, result.events().getFirst());
		assertEquals(aggregate.addedShareholderIds(), event.shareholderIds());
		assertEquals(3, result.inputs().getFirst().version());
	}

	@Test
	void allTenSpecializedAdaptersCanPopulateTheGenericDispatcher() {
		PotContextPort potContexts = new PotContextPort() { };
		ExpenseContextPort expenseContexts = new ExpenseContextPort() { };
		PotGlobalVersionPort versions = new PotGlobalVersionPort() { };
		PotHeaderPort potHeaders = new PotHeaderPort() { };
		ExpenseHeaderPort expenseHeaders = new ExpenseHeaderPort() { };
		ExpenseSharesPort expenseShares = new ExpenseSharesPort() { };
		PotShareholdersPort shareholders = new PotShareholdersPort() {
			@Override public PotShareholders loadActiveAtVersion(PotId potId, long version) { throw new AssertionError(); }
			@Override public void save(PotShareholders value, PotGlobalVersion current, PotGlobalVersion next) {
				throw new AssertionError();
			}
		};
		List<CommandUseCase<?>> adapters = List.of(
				new CreatePotCommandUseCaseAdapter(versions, potHeaders, new CreatePotAuthorizationPolicy()),
				new CreateExpenseCommandUseCaseAdapter(potContexts, versions, expenseHeaders, expenseShares,
						new CreateExpenseAuthorizationPolicy()),
				new AddPotShareholdersCommandUseCaseAdapter(potContexts, shareholders, versions,
						new AddPotShareholdersAuthorizationPolicy()),
				new DeletePotCommandUseCaseAdapter(potContexts, potHeaders, versions, new DeletePotAuthorizationPolicy()),
				new DeleteExpenseCommandUseCaseAdapter(expenseContexts, expenseHeaders, versions,
						new DeleteExpenseAuthorizationPolicy()),
				new UpdatePotDetailsCommandUseCaseAdapter(potContexts, potHeaders, versions,
						new UpdatePotDetailsAuthorizationPolicy()),
				new UpdateExpenseDetailsCommandUseCaseAdapter(expenseContexts, expenseHeaders, versions,
						new UpdateExpenseDetailsAuthorizationPolicy()),
				new UpdateExpenseSharesCommandUseCaseAdapter(expenseContexts, expenseShares, versions,
						new UpdateExpenseSharesAuthorizationPolicy()),
				new UpdatePotShareholdersDetailsCommandUseCaseAdapter(potContexts, shareholders, versions,
						new UpdatePotShareholdersDetailsAuthorizationPolicy()),
				new UpdatePotShareholdersWeightsCommandUseCaseAdapter(potContexts, shareholders, versions,
						new UpdatePotShareholdersWeightsAuthorizationPolicy()));

		new CommandDispatcher(adapters);
		assertEquals(10, adapters.stream().map(CommandUseCase::commandClass).distinct().count());
	}

	@Test
	void keepsEventsAndProvenanceIsolatedBetweenConcurrentInvocations()
			throws ExecutionException, InterruptedException {
		UUID firstUser = UUID.randomUUID();
		UUID secondUser = UUID.randomUUID();
		CountDownLatch entered = new CountDownLatch(2);
		TestAdapter adapter = new TestAdapter((invocation, userContext) -> {
			entered.countDown();
			try {
				entered.await();
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
			PotId invocationPotId = PotId.of(userContext.userId().value());
			PotContextPort contextPort = invocation.recording(new PotContextPort() {
				@Override public UpdatePotDetailsContext loadUpdatePotDetailsContext(PotId potId) {
					return new UpdatePotDetailsContext(new PotGlobalVersion(potId, 4), false,
							userContext.userId());
				}
			});
			contextPort.loadUpdatePotDetailsContext(invocationPotId);
			invocation.publish(new PotDetailsUpdatedEvent(invocationPotId, 5));
		});

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<CommandUseCaseResult> first = executor.submit(
					() -> adapter.execute(authorization(firstUser, Set.of()), command()));
			Future<CommandUseCaseResult> second = executor.submit(
					() -> adapter.execute(authorization(secondUser, Set.of()), command()));

			assertInvocationBelongsTo(first.get(), firstUser);
			assertInvocationBelongsTo(second.get(), secondUser);
		}
	}

	private static void assertRejected(String expectedCode, RuntimeException failure) {
		TestAdapter adapter = new TestAdapter((invocation, ignored) -> { throw failure; });
		CommandUseCaseResult.Rejected result = assertInstanceOf(CommandUseCaseResult.Rejected.class,
				adapter.execute(authorization(Set.of()), command()));
		assertEquals(expectedCode, result.reason().code());
		assertEquals(List.of(), result.inputs());
	}

	private static AuthorizationSnapshot authorization(Set<Permission> permissions) {
		return authorization(USER_ID, permissions);
	}

	private static AuthorizationSnapshot authorization(UUID userId, Set<Permission> permissions) {
		return new AuthorizationSnapshot(new PocomaUserId(userId), permissions,
				Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
				Instant.parse("2027-01-01T00:00:00Z"), "test");
	}

	private static void assertInvocationBelongsTo(CommandUseCaseResult executionResult, UUID expectedId) {
		CommandUseCaseResult.Succeeded result = assertInstanceOf(CommandUseCaseResult.Succeeded.class, executionResult);
		assertEquals(expectedId.toString(), result.inputs().getFirst().id());
		PotDetailsUpdatedEvent event = assertInstanceOf(PotDetailsUpdatedEvent.class, result.events().getFirst());
		assertEquals(PotId.of(expectedId), event.potId());
	}

	private static CreatePotCommand command() {
		return new CreatePotCommand("Trip", USER_ID);
	}

	private static final class TestAdapter extends AbstractPotCommandUseCaseAdapter<CreatePotCommand> {
		private final AdaptedExecution execution;
		private TestAdapter(AdaptedExecution execution) { this.execution = execution; }
		@Override public Class<CreatePotCommand> commandClass() { return CreatePotCommand.class; }
		@Override public CommandUseCaseResult execute(AuthorizationSnapshot authorization, CreatePotCommand command) {
			return executeAdapted(authorization, command, execution);
		}
	}

	private static final class TechnicalException extends RuntimeException {
	}
}
