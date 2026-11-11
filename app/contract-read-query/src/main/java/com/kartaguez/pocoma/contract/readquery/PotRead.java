package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.projection.v3.*;

import static com.kartaguez.pocoma.domain.projection.v3.SubobjectContent.*;
import static com.kartaguez.pocoma.domain.projection.v3.SubobjectKind.*;
import static com.kartaguez.pocoma.contract.readquery.ReadPotV3Result.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.ExternalUserIdentity;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.*;

public interface PotRead {
    public interface Queries {
        ReadPotV3Result<Pot> current(ExternalIdentity eui, String token, UUID id, long minimum);
        ReadPotV3Result<Pot> at(ExternalIdentity eui, String token, UUID id, long version);
        ReadPotV3Result<PotBalance> balance(ExternalIdentity eui, String token, UUID id, long version);
        ReadPotV3Result<List<Expense>> expenses(ExternalIdentity eui, String token, UUID id, long version, List<ExpenseRef> references);
        ReadPotV3Result<List<UUID>> visible(ExternalIdentity eui, String token, UUID after, int limit);
    }
    public interface BindingReader {
        Optional<ExternalUserIdentity> read(ExternalIdentity eui, RecoveryReadContext context);
    }

}
