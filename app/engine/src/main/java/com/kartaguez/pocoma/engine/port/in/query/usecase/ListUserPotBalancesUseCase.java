package com.kartaguez.pocoma.engine.port.in.query.usecase;

import java.util.List;

import com.kartaguez.pocoma.engine.port.in.query.intent.ListUserPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.UserPotBalanceSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface ListUserPotBalancesUseCase {

	List<UserPotBalanceSnapshot> listUserPotBalances(UserContext userContext, ListUserPotBalancesQuery query);
}
