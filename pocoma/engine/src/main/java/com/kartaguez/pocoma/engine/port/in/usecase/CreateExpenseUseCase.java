package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface CreateExpenseUseCase {

	ExpenseSharesSnapshot createExpense(UserContext userContext, CreateExpenseCommand command);
}
