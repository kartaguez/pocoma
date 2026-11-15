package com.kartaguez.pocoma.contract.readquery;
import com.kartaguez.pocoma.domain.projection.*;

import static com.kartaguez.pocoma.contract.readquery.ReadPotResult.*;
import com.kartaguez.pocoma.domain.pot.*;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
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
        ReadPotResult<Link> link(ExternalIdentity eui, UUID creationRequestId);

        ReadPotResult<List<Place>> places(
                ExternalIdentity eui, String bindingToken, String invitationToken);
    }

}
