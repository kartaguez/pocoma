package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.snapshot.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdateExpenseSharesUseCase {

	ExpenseSharesSnapshot updateExpenseShares(UserContext userContext, UpdateExpenseSharesCommand command);
}
