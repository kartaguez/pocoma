package com.kartaguez.pocoma.engine.port.in.query.usecase;

import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotBalancesSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface GetPotBalancesUseCase {

	PotBalancesSnapshot getPotBalances(UserContext userContext, GetPotBalancesQuery query);
}
