package com.kartaguez.pocoma.engine.command.model;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

/** User and capabilities prepared authoritatively for one Command execution. */
public interface CommandExecutionAuthorization {

	PocomaUserId userId();

	Set<Permission> permissions();
}
