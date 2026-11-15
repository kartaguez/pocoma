package com.kartaguez.pocoma.domain.pot;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public record ExpenseHeader(PotId potId, ExpenseId expenseId, boolean deleted) {
    public ExpenseHeader {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(expenseId);

    }
}
