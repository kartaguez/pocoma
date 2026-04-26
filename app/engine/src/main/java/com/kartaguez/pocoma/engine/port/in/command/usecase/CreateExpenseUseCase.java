package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface CreateExpenseUseCase {

	ExpenseSharesSnapshot createExpense(UserContext userContext, CreateExpenseCommand command);
}
