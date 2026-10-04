package com.kartaguez.pocoma.engine.read.pot;

import java.util.Objects;
import java.util.Set;
import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityResolverPort;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

/** Historical E→U adapter, followed by exact AUTH@V and READ_POT@V orchestration. */
public final class ReadPotForExternalIdentityService implements ReadPotForExternalIdentityUseCase {
    private final ReadPotUseCase pots;
    private final ExternalIdentityResolverPort identities;
    private final ExternalAuthorityPermissionTranslator permissions;
    private final TransactionRunner transactions;

    public ReadPotForExternalIdentityService(ReadPotUseCase pots, ExternalIdentityResolverPort identities,
            ExternalAuthorityPermissionTranslator permissions, TransactionRunner transactions) {
        this.pots = Objects.requireNonNull(pots);
        this.identities = Objects.requireNonNull(identities);
        this.permissions = Objects.requireNonNull(permissions);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override public ReadPotResult read(ExternalIdentity identity, Set<String> externalAuthorities, PotId potId, long version) {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(externalAuthorities);
        var resolved = transactions.runInTransaction(() -> identities.findUserId(identity));
        if (resolved.isEmpty()) return new ReadPotResult.Forbidden();
        var capabilities = new TokenCapabilities(permissions.translate(externalAuthorities));
        return pots.read(new UserId(resolved.orElseThrow().value()), capabilities, potId, version);
    }
}
