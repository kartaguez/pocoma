package com.kartaguez.pocoma.engine.port.in.query.usecase;

import java.util.List;

import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListPotExpensesQuery;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface ListPotExpensesUseCase {

	List<ExpenseHeaderSnapshot> listPotExpenses(UserContext userContext, ListPotExpensesQuery query);
}
