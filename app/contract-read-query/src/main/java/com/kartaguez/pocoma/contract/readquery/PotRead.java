package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.projection.*;

import static com.kartaguez.pocoma.domain.projection.SubobjectContent.*;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.*;
import static com.kartaguez.pocoma.contract.readquery.ReadPotResult.*;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.ExternalUserIdentity;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;

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
