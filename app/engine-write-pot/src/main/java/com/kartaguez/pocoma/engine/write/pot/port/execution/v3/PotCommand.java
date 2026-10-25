package com.kartaguez.pocoma.engine.write.pot.port.execution.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.time.LocalDate;
import java.util.*;

/** Immutable admitted intention. The actor is resolved by WRITE, never supplied here. */
public record PotCommand(
        UUID requestId,
        Eui requester,
        String bindingToken,
        PotId potId,
        long observedVersion,
        Intent intent) {
    public PotCommand {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(requester);
        Objects.requireNonNull(potId);
        Objects.requireNonNull(intent);
        if (bindingToken == null || bindingToken.isBlank())
            throw new IllegalArgumentException("Binding token required");
        if (observedVersion < 0
                || (intent instanceof CreatePot ? observedVersion != 0 : observedVersion == 0))
            throw new IllegalArgumentException("Invalid observation");
    }

    @Override
    public String toString() {
        return "PotCommand[requestId=" + requestId + ", potId=" + potId + "]";
    }

    public record Eui(String issuer, String subject) {
        public Eui {
            Objects.requireNonNull(issuer);
            Objects.requireNonNull(subject);
        }
    }

    public sealed interface Intent
            permits CreatePot,
                    UpdatePotDetails,
                    DeletePot,
                    AddShareholders,
                    UpdateShareholderDetails,
                    UpdateShareholderWeights,
                    DeleteShareholder,
                    CreateExpense,
                    UpdateExpenseDetails,
                    UpdateExpenseFinancials,
                    DeleteExpense {}

    public record CreatePot(Label label) implements Intent {
        public CreatePot {
            Objects.requireNonNull(label);
        }
    }

    public record UpdatePotDetails(Label label) implements Intent {
        public UpdatePotDetails {
            Objects.requireNonNull(label);
        }
    }

    public record DeletePot() implements Intent {}

    public record AddShareholders(List<ShareholderTransitions.Creation> targets) implements Intent {
        public AddShareholders {
            targets = List.copyOf(targets);
        }
    }

    public record UpdateShareholderDetails(List<ShareholderTransitions.DetailsIntent> targets)
            implements Intent {
        public UpdateShareholderDetails {
            targets = List.copyOf(targets);
        }
    }

    public record UpdateShareholderWeights(List<ShareholderTransitions.WeightIntent> targets)
            implements Intent {
        public UpdateShareholderWeights {
            targets = List.copyOf(targets);
        }
    }

    public record DeleteShareholder(ShareholderId id) implements Intent {
        public DeleteShareholder {
            Objects.requireNonNull(id);
        }
    }

    public record CreateExpense(ExpenseDetails details, ExpenseFinancials financials)
            implements Intent {
        public CreateExpense {
            Objects.requireNonNull(details);
            Objects.requireNonNull(financials);
        }
    }

    public record UpdateExpenseDetails(ExpenseId id, Label label, LocalDate date)
            implements Intent {
        public UpdateExpenseDetails {
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(date);
        }
    }

    public record UpdateExpenseFinancials(ExpenseFinancials financials) implements Intent {
        public UpdateExpenseFinancials {
            Objects.requireNonNull(financials);
        }
    }

    public record DeleteExpense(ExpenseId id) implements Intent {
        public DeleteExpense {
            Objects.requireNonNull(id);
        }
    }
}
