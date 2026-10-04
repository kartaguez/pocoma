package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.CreateExpenseInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface CreateExpenseUseCase {

	ExpenseSharesSnapshot createExpense(UserContext userContext, CreateExpenseInput command);
}
