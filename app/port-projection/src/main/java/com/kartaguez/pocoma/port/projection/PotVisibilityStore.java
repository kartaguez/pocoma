package com.kartaguez.pocoma.port.projection;
import com.kartaguez.pocoma.domain.projection.PotVisibility;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;
public interface PotVisibilityStore {
    record Receipt(PotVisibility state,Set<UUID> disabled,boolean advanced) { public Receipt { Objects.requireNonNull(state); disabled=Set.copyOf(disabled); } }
    Receipt apply(PotVisibility value);
    void requireCompletionReady(Receipt receipt);
    Optional<PotVisibility> read(UUID pot,RecoveryReadContext context);
    List<UUID> visiblePots(UUID user,UUID after,int limit,RecoveryReadContext context);
    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication(Receipt receipt);

    ReadEvidence evidence(PotVisibility incoming, PotVisibility expected, RecoveryReadContext context);
}
