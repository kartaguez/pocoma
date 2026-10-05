package com.kartaguez.pocoma.engine.read.pot;

import java.util.Set;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public interface ReadPotForExternalIdentityUseCase {
    ReadPotResult read(ExternalIdentity identity, Set<String> externalAuthorities, PotId potId, long version);
}
