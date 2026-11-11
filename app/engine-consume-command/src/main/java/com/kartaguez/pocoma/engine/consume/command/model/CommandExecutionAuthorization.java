package com.kartaguez.pocoma.engine.consume.command.model;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.identity.PocomaUserId;

/** User and capabilities prepared authoritatively for one Command execution. */
public interface CommandExecutionAuthorization {

	PocomaUserId userId();

	Set<Permission> permissions();
}
