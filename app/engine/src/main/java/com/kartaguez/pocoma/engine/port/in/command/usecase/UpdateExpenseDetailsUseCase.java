package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdateExpenseDetailsUseCase {

	ExpenseHeaderSnapshot updateExpenseDetails(UserContext userContext, UpdateExpenseDetailsCommand command);
}
