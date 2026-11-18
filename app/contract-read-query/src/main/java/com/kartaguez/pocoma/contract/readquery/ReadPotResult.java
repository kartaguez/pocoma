package com.kartaguez.pocoma.contract.readquery;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.value.Fraction;

/** Public internal views deliberately exclude User IDs, authorization sets and binding tokens. */
public sealed interface ReadPotResult<T> {
    record Available<T>(T value, long servedVersion) implements ReadPotResult<T> {
        public Available {
            Objects.requireNonNull(value);
        }
    }

    record NotFound<T>() implements ReadPotResult<T> {}

    record NotReady<T>() implements ReadPotResult<T> {}

    record Unavailable<T>() implements ReadPotResult<T> {}

    record InvalidInput<T>() implements ReadPotResult<T> {}

    record ExpenseRef(
            UUID id,
            long headerVersion,
            long detailsVersion,
            long financialsVersion,
            boolean deleted) {
        public ExpenseRef {
            Objects.requireNonNull(id);
            if (headerVersion < 1 || detailsVersion < 1 || financialsVersion < 1)
                throw new IllegalArgumentException("positive local versions required");
        }
    }

    record Shareholder(
            UUID id,
            long headerVersion,
            long detailsVersion,
            long weightVersion,
            boolean deleted,
            boolean isFree,
            String name,
            Fraction defaultWeight) {}

    record Pot(
            UUID id,
            String label,
            long headerVersion,
            long detailsVersion,
            List<Shareholder> shareholders,
            Map<UUID, Fraction> balances,
            List<ExpenseRef> expenses) {
        public Pot {
            shareholders = List.copyOf(shareholders);
            balances = Collections.unmodifiableMap(new LinkedHashMap<>(balances));
            expenses = List.copyOf(expenses);
        }
    }

    record Participation(UUID shareholderId, Optional<Fraction> specificWeight) {}

    record Expense(
            ExpenseRef reference,
            String label,
            LocalDate date,
            Fraction amount,
            UUID payerId,
            List<Participation> participants) {
        public Expense {
            participants = List.copyOf(participants);
        }
    }
}
