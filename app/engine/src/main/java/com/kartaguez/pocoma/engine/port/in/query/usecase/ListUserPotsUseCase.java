package com.kartaguez.pocoma.engine.port.in.query.usecase;

import java.util.List;

import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface ListUserPotsUseCase {

	List<PotHeaderSnapshot> listUserPots(UserContext userContext);
}
