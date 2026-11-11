package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.projection.v3.*;

import static com.kartaguez.pocoma.contract.readquery.ReadPotV3Result.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

public interface InvitationRead {
    public record Link(UUID invitationId, UUID potId, Instant expiresAt, String link) {
        @Override
        public String toString() {
            return "InvitationLink[redacted]";
        }
    }
    public record Place(UUID shareholderId, String name) {}
    public interface Queries {
        ReadPotV3Result<Link> link(ExternalIdentity eui, UUID creationRequestId);

        ReadPotV3Result<List<Place>> places(
                ExternalIdentity eui, String bindingToken, String invitationToken);
    }

}
