package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface DeleteExpenseUseCase {

	ExpenseHeaderSnapshot deleteExpense(UserContext userContext, DeleteExpenseCommand command);
}
