package com.kartaguez.pocoma.engine.port.in.query.usecase;

import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotViewSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface GetPotUseCase {

	PotViewSnapshot getPot(UserContext userContext, GetPotQuery query);
}
