package com.kartaguez.pocoma.contract.readquery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;

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
