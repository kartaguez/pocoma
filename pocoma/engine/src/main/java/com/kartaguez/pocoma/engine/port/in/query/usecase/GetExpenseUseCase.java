package com.kartaguez.pocoma.engine.port.in.query.usecase;

import com.kartaguez.pocoma.engine.port.in.query.intent.GetExpenseQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.ExpenseViewSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface GetExpenseUseCase {

	ExpenseViewSnapshot getExpense(UserContext userContext, GetExpenseQuery query);
}
