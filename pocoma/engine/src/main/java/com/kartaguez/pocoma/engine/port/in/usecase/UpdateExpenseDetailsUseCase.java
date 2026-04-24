package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdateExpenseDetailsUseCase {

	ExpenseHeaderSnapshot updateExpenseDetails(UserContext userContext, UpdateExpenseDetailsCommand command);
}
