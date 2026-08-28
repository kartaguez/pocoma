package com.kartaguez.pocoma.engine.security;

import java.util.Set;

import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public record UserContext(UserId userId, Set<Scope> scopes) {
}
