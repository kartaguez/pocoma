package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.UpdateExpenseDetailsInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface UpdateExpenseDetailsUseCase {

	ExpenseHeaderSnapshot updateExpenseDetails(UserContext userContext, UpdateExpenseDetailsInput command);
}
