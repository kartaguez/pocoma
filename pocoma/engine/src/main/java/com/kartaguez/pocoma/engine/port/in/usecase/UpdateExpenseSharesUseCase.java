package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdateExpenseSharesUseCase {

	ExpenseSharesSnapshot updateExpenseShares(UserContext userContext, UpdateExpenseSharesCommand command);
}
