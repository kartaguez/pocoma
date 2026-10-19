package com.kartaguez.pocoma.engine.consume.registration;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.port.binding.authority.BindingAcquisitionAccepted;
import com.kartaguez.pocoma.port.transaction.*;

/** TARGET initializer grammar. Does not replace or wire ExecuteRegistrationService. */
class RegistrationPublicationContractTest {
    @FunctionalInterface interface TargetAcquire {
        BindingAcquisitionAccepted acquire(Runnable initializer);
    }
    private static void execute(boolean existing, TargetAcquire binding, Supplier<EventRef> userFact,
            PublishedEvents declarations) {
        if (existing) return;
        var accepted = binding.acquire(() -> declarations.recordPublishedEvent(userFact.get()));
        accepted.fact().ifPresent(declarations::recordPublishedEvent);
    }
    @Test void userCreatedAcceptanceIsDeclaredInInitializerBeforeWinningAttachedAcceptance() {
        var user = new EventRef(EventFamily.USER_CREATED_FACT, UUID.randomUUID());
        var attached = new EventRef(EventFamily.BINDING_FACT, UUID.randomUUID());
        var phases = new ArrayList<String>();
        var declared = new ArrayList<EventRef>();
        execute(false, initializer -> {
            phases.add("arbitration");
            initializer.run();
            assertEquals(List.of(user), declared);
            phases.add("attached-accepted");
            return new BindingAcquisitionAccepted(BindingAcquireResult.acquired(new BindingId(UUID.randomUUID())),
                    Optional.of(attached));
        }, () -> { phases.add("user-accepted"); return user; }, ref -> {
            phases.add("declared:" + ref.family()); declared.add(ref);
        });
        assertEquals(List.of(user, attached), declared);
        assertEquals(List.of("arbitration", "user-accepted", "declared:USER_CREATED_FACT",
                "attached-accepted", "declared:BINDING_FACT"), phases);
    }
    @Test void existingConflictAndFailedAcceptanceHaveNoFictitiousDeclarations() {
        var declared = new ArrayList<EventRef>();
        Supplier<EventRef> forbidden = () -> { fail("initializer must not run"); return null; };
        TargetAcquire conflict = initializer -> new BindingAcquisitionAccepted(BindingAcquireResult.conflict(), Optional.empty());
        execute(true, initializer -> { fail("existing outcome must not reacquire"); return null; }, forbidden, declared::add);
        execute(false, conflict, forbidden, declared::add);
        assertThrows(IllegalStateException.class, () -> execute(false, initializer -> {
            initializer.run(); fail("no attached acceptance after failure"); return null;
        }, () -> { throw new IllegalStateException("user fact refused"); }, declared::add));
        assertTrue(declared.isEmpty());
    }
}
