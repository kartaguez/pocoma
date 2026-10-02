package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

public record CommandResultVisibility(ExternalIdentity identity) {
    public CommandResultVisibility { requireNonNull(identity, "identity must not be null"); }
}
