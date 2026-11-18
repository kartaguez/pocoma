package com.kartaguez.pocoma.domain.pot.expense;


import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

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
