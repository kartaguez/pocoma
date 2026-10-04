package com.kartaguez.pocoma.engine.consume.command.pot.dispatch;

import java.util.stream.Collectors;

import com.kartaguez.pocoma.engine.consume.command.pot.intent.*;
import com.kartaguez.pocoma.engine.write.pot.input.*;

/** Temporary B16 bridge from durable Command payloads to Pot WRITE inputs. */
final class PotCommandInputMapper {
    private PotCommandInputMapper() {}

    static CreatePotInput toInput(CreatePotCommand c) {
        return new CreatePotInput(c.label(), c.creatorId());
    }
    static DeletePotInput toInput(DeletePotCommand c) {
        return new DeletePotInput(c.potId(), c.expectedVersion());
    }
    static DeleteExpenseInput toInput(DeleteExpenseCommand c) {
        return new DeleteExpenseInput(c.expenseId(), c.expectedVersion());
    }
    static UpdatePotDetailsInput toInput(UpdatePotDetailsCommand c) {
        return new UpdatePotDetailsInput(c.potId(), c.label(), c.expectedVersion());
    }
    static UpdateExpenseDetailsInput toInput(UpdateExpenseDetailsCommand c) {
        return new UpdateExpenseDetailsInput(c.expenseId(), c.payerId(), c.amountNumerator(),
                c.amountDenominator(), c.label(), c.date(), c.expectedVersion());
    }
    static CreateExpenseInput toInput(CreateExpenseCommand c) {
        return new CreateExpenseInput(c.potId(), c.payerId(), c.amountNumerator(), c.amountDenominator(),
                c.label(), c.date(), c.shares().stream()
                    .map(s -> new CreateExpenseInput.ExpenseShareInput(s.shareholderId(),
                            s.weightNumerator(), s.weightDenominator()))
                    .collect(Collectors.toUnmodifiableSet()), c.expectedVersion());
    }
    static AddPotShareholdersInput toInput(AddPotShareholdersCommand c) {
        return new AddPotShareholdersInput(c.potId(), c.shareholders().stream()
                .map(s -> new AddPotShareholdersInput.ShareholderInput(s.name(),
                        s.weightNumerator(), s.weightDenominator()))
                .collect(Collectors.toUnmodifiableSet()), c.expectedVersion());
    }
    static UpdateExpenseSharesInput toInput(UpdateExpenseSharesCommand c) {
        return new UpdateExpenseSharesInput(c.expenseId(), c.shares().stream()
                .map(s -> new UpdateExpenseSharesInput.ExpenseShareInput(s.shareholderId(),
                        s.weightNumerator(), s.weightDenominator()))
                .collect(Collectors.toUnmodifiableSet()), c.expectedVersion());
    }
    static UpdatePotShareholdersDetailsInput toInput(UpdatePotShareholdersDetailsCommand c) {
        return new UpdatePotShareholdersDetailsInput(c.potId(), c.shareholders().stream()
                .map(s -> new UpdatePotShareholdersDetailsInput.ShareholderDetailsInput(
                        s.shareholderId(), s.name(), s.userId()))
                .collect(Collectors.toUnmodifiableSet()), c.expectedVersion());
    }
    static UpdatePotShareholdersWeightsInput toInput(UpdatePotShareholdersWeightsCommand c) {
        return new UpdatePotShareholdersWeightsInput(c.potId(), c.shareholders().stream()
                .map(s -> new UpdatePotShareholdersWeightsInput.ShareholderWeightInput(
                        s.shareholderId(), s.weightNumerator(), s.weightDenominator()))
                .collect(Collectors.toUnmodifiableSet()), c.expectedVersion());
    }
}
