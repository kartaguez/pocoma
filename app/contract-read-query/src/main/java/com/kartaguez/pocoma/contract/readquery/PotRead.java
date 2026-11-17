package com.kartaguez.pocoma.contract.readquery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.contract.readquery.ReadPotResult.Expense;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult.ExpenseRef;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult.Pot;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.ExternalUserIdentity;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

public interface PotRead {
    public interface Queries {
        ReadPotResult<Pot> current(ExternalIdentity eui, String token, UUID id, long minimum);
        ReadPotResult<Pot> at(ExternalIdentity eui, String token, UUID id, long version);
        ReadPotResult<PotBalance> balance(ExternalIdentity eui, String token, UUID id, long version);
        ReadPotResult<List<Expense>> expenses(ExternalIdentity eui, String token, UUID id, long version, List<ExpenseRef> references);
        ReadPotResult<List<UUID>> visible(ExternalIdentity eui, String token, UUID after, int limit);
    }
    public interface BindingReader {
        Optional<ExternalUserIdentity> read(ExternalIdentity eui, RecoveryReadContext context);
    }

}
