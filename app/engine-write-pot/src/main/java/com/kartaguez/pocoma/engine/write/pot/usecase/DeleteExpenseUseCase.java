package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.DeleteExpenseInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface DeleteExpenseUseCase {

	ExpenseHeaderSnapshot deleteExpense(UserContext userContext, DeleteExpenseInput command);
}
