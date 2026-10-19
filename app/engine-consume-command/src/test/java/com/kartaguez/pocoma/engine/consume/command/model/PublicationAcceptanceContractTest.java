package com.kartaguez.pocoma.engine.consume.command.model;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.port.binding.authority.*;
import com.kartaguez.pocoma.port.transaction.*;

/** TARGET signatures are test-local, never parallel production ports or a second collection. */
class PublicationAcceptanceContractTest {
    static EventRef ref(EventFamily family, long id) { return new EventRef(family, new UUID(0, id)); }
    static CommandExecutionArtifact artifact(EventRef ref) {
        return new CommandExecutionArtifact("EVENT", "PotCreated", ref.eventId().toString(),
                OptionalLong.empty(), Optional.empty(), Instant.EPOCH);
    }
    static BusinessEventsAccepted accepted(List<EventRef> refs) {
        return new BusinessEventsAccepted(refs.stream().map(PublicationAcceptanceContractTest::artifact).toList(), refs);
    }
    // One possible TARGET caller. The port only accepts; orchestration alone declares.
    static void append(Supplier<BusinessEventsAccepted> port, PublishedEvents declarations) {
        var acceptance = port.get();
        acceptance.events().forEach(declarations::recordPublishedEvent);
    }
    @Test void acceptanceDoesNotDeclareAndOrchestrationPreservesListsAndSuccessiveCallOrder() {
        var a = ref(EventFamily.BUSINESS_EVENT, 9);
        var b = ref(EventFamily.BUSINESS_EVENT, 2); // Deliberately opposite UUID order.
        var c = ref(EventFamily.BUSINESS_EVENT, 1);
        var source = new ArrayList<>(List.of(a, b));
        var acceptance = accepted(source);
        source.clear();
        var declared = new ArrayList<EventRef>();
        assertTrue(declared.isEmpty());
        assertEquals(List.of(a, b), acceptance.events());
        assertThrows(UnsupportedOperationException.class, () -> acceptance.events().clear());
        append(() -> acceptance, declared::add);
        append(() -> accepted(List.of(c)), declared::add);
        append(() -> accepted(List.of()), declared::add);
        assertEquals(List.of(a, b, c), declared);
    }
    @Test void failedAcceptanceAndEmptyPublicationNeverDeclare() {
        var declared = new ArrayList<EventRef>();
        assertThrows(IllegalStateException.class, () -> append(() -> {
            throw new IllegalStateException("refused");
        }, declared::add));
        append(() -> accepted(List.of()), declared::add);
        assertTrue(declared.isEmpty());
    }
    @Test void invalidOrReorderedAcceptanceCannotMasqueradeAsThePublishedArtifacts() {
        var a = ref(EventFamily.BUSINESS_EVENT, 9);
        var b = ref(EventFamily.BUSINESS_EVENT, 2);
        assertThrows(IllegalArgumentException.class, () -> new BusinessEventsAccepted(List.of(artifact(a)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> accepted(List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () -> new BusinessEventsAccepted(
                List.of(artifact(a), artifact(b)), List.of(b, a)));
        assertThrows(IllegalArgumentException.class, () -> accepted(List.of(ref(EventFamily.BINDING_FACT, 9))));
        assertThrows(NullPointerException.class, () -> new EventRef(null, UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> new EventRef(EventFamily.BUSINESS_EVENT, null));
    }
    @Test void bindingAuthorityOnlyExposesWinningFactsAndPreservesSuppliedIdentity() {
        var fact = ref(EventFamily.BINDING_FACT, 7);
        var result = BindingAcquireResult.acquired(new BindingId(UUID.randomUUID()));
        var acquired = new BindingAcquisitionAccepted(result, Optional.of(fact));
        assertSame(result, acquired.result());
        assertSame(fact, acquired.fact().orElseThrow());
        var declared = new ArrayList<EventRef>();
        acquired.fact().ifPresent(declared::add);
        new BindingAcquisitionAccepted(BindingAcquireResult.conflict(), Optional.empty()).fact().ifPresent(declared::add);
        new BindingDetachmentAccepted(BindingDetachResult.NOT_CURRENT, Optional.empty()).fact().ifPresent(declared::add);
        new BindingDetachmentAccepted(BindingDetachResult.DETACHED, Optional.of(ref(EventFamily.BINDING_FACT, 8)))
                .fact().ifPresent(declared::add);
        assertEquals(List.of(fact, ref(EventFamily.BINDING_FACT, 8)), declared);
        assertThrows(IllegalArgumentException.class, () -> new BindingAcquisitionAccepted(result, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new BindingAcquisitionAccepted(
                BindingAcquireResult.conflict(), Optional.of(fact)));
        assertThrows(IllegalArgumentException.class, () -> new BindingDetachmentAccepted(
                BindingDetachResult.NOT_CURRENT, Optional.of(fact)));
        assertThrows(IllegalArgumentException.class, () -> new BindingDetachmentAccepted(
                BindingDetachResult.DETACHED, Optional.of(ref(EventFamily.USER_CREATED_FACT, 1))));
    }
    @Test void terminalAppliedRejectedFailedAndRecoveryDeclareWhenTheCallbackExecutes() {
        var declared = new ArrayList<EventRef>();
        var command = new com.kartaguez.pocoma.contracts.command.CommandId(UUID.randomUUID());
        var outcomes = List.of(new CommandOutcome.Applied(command, UUID.randomUUID(), 1, Instant.EPOCH),
                new CommandOutcome.Rejected(command, "REJECTED", Instant.EPOCH),
                new CommandOutcome.Failed(command, CommandOutcome.PUBLIC_FAILURE_CODE, Instant.EPOCH),
                new CommandOutcome.Rejected(command, "BINDING_CHANGED", Instant.EPOCH));
        for (int i = 0; i < outcomes.size(); i++) {
            CommandOutcome outcome = outcomes.get(i);
            EventRef acceptance = ref(EventFamily.COMMAND_TERMINAL_EVENT, i + 1);
            java.util.function.Function<CommandOutcome, EventRef> targetPort = acceptedOutcome -> {
                assertSame(outcome, acceptedOutcome);
                return acceptance;
            };
            PublishedEvents declarations = declared::add;
            Runnable terminalEffect = () -> declarations.recordPublishedEvent(targetPort.apply(outcome));
            assertEquals(i, declared.size()); // FAILED/TR declare during execution, never at callback creation.
            terminalEffect.run();
            assertEquals(acceptance, declared.getLast());
        }
    }
    @Test void contractAssertionsDetectOmittedFictitiousAndDuplicateDeclarations() {
        var expected = List.of(ref(EventFamily.BUSINESS_EVENT, 4), ref(EventFamily.BUSINESS_EVENT, 2));
        for (var wrong : List.of(List.of(expected.getFirst()),
                List.of(expected.getFirst(), ref(EventFamily.BUSINESS_EVENT, 99)),
                List.of(expected.getFirst(), expected.getFirst()))) {
            assertThrows(AssertionError.class, () -> assertEquals(expected, wrong));
        }
        // Runtime duplicate poisoning is intentionally a 4B proof, not implemented by this double.
        assertNotEquals(new EventRef(EventFamily.BUSINESS_EVENT, new UUID(0, 1)),
                new EventRef(EventFamily.BINDING_FACT, new UUID(0, 1)));
    }
}
