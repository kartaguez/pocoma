package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.projection.v3.*;

import com.kartaguez.pocoma.domain.pot.v3.Fraction;

import java.time.LocalDate;
import java.util.*;

/** Public internal views deliberately exclude User IDs, authorization sets and binding tokens. */
public sealed interface ReadPotV3Result<T> {
    record Available<T>(T value, long servedVersion) implements ReadPotV3Result<T> {
        public Available {
            Objects.requireNonNull(value);
        }
    }

    record NotFound<T>() implements ReadPotV3Result<T> {}

    record NotReady<T>() implements ReadPotV3Result<T> {}

    record Unavailable<T>() implements ReadPotV3Result<T> {}

    record InvalidInput<T>() implements ReadPotV3Result<T> {}

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
