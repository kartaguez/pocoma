package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface DeleteExpenseUseCase {

	ExpenseHeaderSnapshot deleteExpense(UserContext userContext, DeleteExpenseCommand command);
}
