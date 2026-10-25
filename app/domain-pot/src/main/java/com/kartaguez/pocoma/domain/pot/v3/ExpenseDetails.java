package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record ExpenseDetails(PotId potId, ExpenseId expenseId, Label label, LocalDate date) {
    public ExpenseDetails {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(expenseId);
        Objects.requireNonNull(label);
        Objects.requireNonNull(date);

    }
}
