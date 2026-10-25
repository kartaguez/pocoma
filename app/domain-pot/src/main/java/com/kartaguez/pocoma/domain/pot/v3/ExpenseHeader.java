package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record ExpenseHeader(PotId potId, ExpenseId expenseId, boolean deleted) {
    public ExpenseHeader {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(expenseId);

    }
}
