package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record ExpenseFinancials(PotId potId, ExpenseId expenseId, Amount amount, ShareholderId payerId, Map<ShareholderId, Optional<Weight>> participants) {
    public ExpenseFinancials {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(expenseId);
        Objects.requireNonNull(amount);
        Objects.requireNonNull(payerId);
        Objects.requireNonNull(participants);

        participants = Map.copyOf(participants);
        if (participants.isEmpty()) throw new IllegalArgumentException("Participants required");
    }
    public record Participation(ShareholderId shareholderId, Optional<Weight> specificWeight) {
        public Participation { Objects.requireNonNull(shareholderId); Objects.requireNonNull(specificWeight); }
    }
    /** Accept entries before map construction so duplicate targets cannot be silently overwritten. */
    public static ExpenseFinancials of(PotId potId, ExpenseId expenseId, Amount amount, ShareholderId payerId, List<Participation> entries) {
        var participants = new HashMap<ShareholderId, Optional<Weight>>();
        for (var entry : entries) {
            if (participants.putIfAbsent(entry.shareholderId(), entry.specificWeight()) != null)
                throw new IllegalArgumentException("Duplicate participant");
        }
        return new ExpenseFinancials(potId, expenseId, amount, payerId, participants);
    }
}
