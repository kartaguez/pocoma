package com.kartaguez.pocoma.engine.write.pot.context;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;

class CommandObsolescenceContextsTest {
    private final PotGlobalVersion v = new PotGlobalVersion(PotId.of(UUID.randomUUID()), 8);
    private final UserId creator = UserId.of(UUID.randomUUID());
    private final ShareholderId a = ShareholderId.of(UUID.randomUUID());
    private final ShareholderId b = ShareholderId.of(UUID.randomUUID());

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 9, Long.MAX_VALUE})
    void everyMutationRejectsInvalidObservation(long observed) {
        List<LongConsumer> validations = List.of(
            o -> new AddPotShareholdersContext(v, false, creator).assertAddPreconditions(o),
            o -> new CreateExpenseContext(v, false, creator, Set.of(a), Map.of()).assertCreatePreconditions(o, a, Set.of(a)),
            o -> new UpdatePotDetailsContext(v, false, creator, 1).assertUpdatePreconditions(o),
            o -> new DeletePotContext(v, false, creator).assertDeletePreconditions(o),
            o -> details(Map.of(a, 1L)).assertUpdatePreconditions(o, Set.of(a)),
            o -> weights(Map.of(a, 1L)).assertUpdatePreconditions(o, Set.of(a)),
            o -> expenseDetails(1).assertUpdatePreconditions(o, a),
            o -> expenseShares(List.of(1L)).assertUpdatePreconditions(o, Set.of(a)),
            o -> deleteExpense(1, List.of(1L)).assertDeletePreconditions(o));
        for (var validation : validations) assertRule("INVALID_OBSERVED_VERSION", () -> validation.accept(observed));
    }

    @Test
    void headerChangesAreIndependentOfChildren() {
        new UpdatePotDetailsContext(v, false, creator, 2).assertUpdatePreconditions(3);
        assertRule("OBSOLETE_COMMAND", () -> new UpdatePotDetailsContext(v, false, creator, 4).assertUpdatePreconditions(3));
        assertRule("OBSOLETE_COMMAND", () -> new DeletePotContext(v, false, creator).assertDeletePreconditions(7));
        new DeletePotContext(v, false, creator).assertDeletePreconditions(8);
    }

    @Test
    void onlyTargetedShareholdersConflictAndDetailsWeightsShareOneWitness() {
        var starts = Map.of(a, 2L, b, 7L);
        details(starts).assertUpdatePreconditions(3, Set.of(a));
        weights(starts).assertUpdatePreconditions(3, Set.of(a));
        assertRule("OBSOLETE_COMMAND", () -> details(starts).assertUpdatePreconditions(3, Set.of(a, b)));
        assertRule("OBSOLETE_COMMAND", () -> weights(starts).assertUpdatePreconditions(3, Set.of(b)));
        assertRule("SHAREHOLDER_NOT_PRESENT", () -> details(Map.of(a, 2L)).assertUpdatePreconditions(3, Set.of(b)));
        assertThrows(IllegalArgumentException.class, () -> new UpdatePotShareholdersWeightsContext(v, false, creator, Set.of(a), Map.of()));
    }

    @Test
    void creationsAcceptOldObservationAndValidateCurrentReferences() {
        new AddPotShareholdersContext(v, false, creator).assertAddPreconditions(1);
        var context = new CreateExpenseContext(v, false, creator, Set.of(a), Map.of());
        context.assertCreatePreconditions(1, a, Set.of(a));
        assertRule("SHAREHOLDER_NOT_PRESENT", () -> context.assertCreatePreconditions(1, b, Set.of(a)));
    }

    @Test
    void expenseComponentsRemainIndependentButDeletionChecksBoth() {
        expenseDetails(2).assertUpdatePreconditions(3, a); // Shares may have changed at 8.
        expenseShares(List.of(2L, 2L)).assertUpdatePreconditions(3, Set.of(a)); // Header may have changed at 8.
        assertRule("OBSOLETE_COMMAND", () -> expenseDetails(4).assertUpdatePreconditions(3, a));
        assertRule("OBSOLETE_COMMAND", () -> expenseShares(List.of(4L)).assertUpdatePreconditions(3, Set.of(a)));
        assertRule("OBSOLETE_COMMAND", () -> deleteExpense(2, List.of(4L)).assertDeletePreconditions(3));
        assertRule("OBSOLETE_COMMAND", () -> deleteExpense(4, List.of(2L)).assertDeletePreconditions(3));
        deleteExpense(2, List.of(2L)).assertDeletePreconditions(3);
    }

    @Test
    void emptyShareSetRequiresExactObservationEvenWithOldHeader() {
        assertRule("OBSOLETE_COMMAND", () -> expenseShares(List.of()).assertUpdatePreconditions(7, Set.of(a)));
        assertRule("OBSOLETE_COMMAND", () -> deleteExpense(1, List.of()).assertDeletePreconditions(7));
        expenseShares(List.of()).assertUpdatePreconditions(8, Set.of(a));
        deleteExpense(1, List.of()).assertDeletePreconditions(8);
    }

    @Test
    void missingOrInconsistentWitnessIsNeverCompatible() {
        assertThrows(NullPointerException.class, () -> expenseShares(null));
        assertThrows(IllegalArgumentException.class, () -> expenseShares(List.of(2L, 4L)));
        assertThrows(IllegalArgumentException.class, () -> expenseShares(List.of(9L)));
        assertThrows(IllegalArgumentException.class, () -> expenseDetails(0));
        assertRule("EXPENSE_ALREADY_DELETED", () -> new DeleteExpenseContext(v, true, false, creator, Map.of(), 8, List.of()).assertDeletePreconditions(1));
        assertRule("POT_ALREADY_DELETED", () -> new UpdatePotDetailsContext(v, true, creator, 8).assertUpdatePreconditions(1));
    }

    private UpdatePotShareholdersDetailsContext details(Map<ShareholderId, Long> starts) {
        return new UpdatePotShareholdersDetailsContext(v, false, creator, starts.keySet(), Map.of(), starts);
    }
    private UpdatePotShareholdersWeightsContext weights(Map<ShareholderId, Long> starts) {
        return new UpdatePotShareholdersWeightsContext(v, false, creator, starts.keySet(), starts);
    }
    private UpdateExpenseDetailsContext expenseDetails(long start) {
        return new UpdateExpenseDetailsContext(v, false, false, creator, Set.of(a), Map.of(), start);
    }
    private UpdateExpenseSharesContext expenseShares(List<Long> starts) {
        return new UpdateExpenseSharesContext(v, false, false, creator, Set.of(a), Map.of(), starts);
    }
    private DeleteExpenseContext deleteExpense(long header, List<Long> starts) {
        return new DeleteExpenseContext(v, false, false, creator, Map.of(), header, starts);
    }
    private static void assertRule(String code, Runnable action) {
        assertEquals(code, assertThrows(BusinessRuleViolationException.class, action::run).ruleCode());
    }
}
