package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.UpdateExpenseSharesInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface UpdateExpenseSharesUseCase {

	ExpenseSharesSnapshot updateExpenseShares(UserContext userContext, UpdateExpenseSharesInput command);
}
