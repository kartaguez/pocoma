package com.kartaguez.pocoma.engine.read.pot.v3;

import static com.kartaguez.pocoma.engine.read.pot.v3.ReadPotV3Result.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.port.projection.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.time.Instant;
import java.util.*;
import java.util.function.*;

/** Restricted Invitation queries; no authority or fallback in WRITE. */
public final class ReadInvitation {
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

    private final com.kartaguez.pocoma.port.projection.v3.InvitationStore invitations;
    private final PotVersionStore pots;
    private final SubobjectVersionStore objects;
    private final ReadPotV3.BindingReader bindings;
    private final Function<Invitation, String> decrypt;
    private final Supplier<Instant> clock;

    public ReadInvitation(
            com.kartaguez.pocoma.port.projection.v3.InvitationStore invitations,
            PotVersionStore pots,
            SubobjectVersionStore objects,
            ReadPotV3.BindingReader bindings,
            Function<Invitation, String> decrypt,
            Supplier<Instant> clock) {
        this.invitations = invitations;
        this.pots = pots;
        this.objects = objects;
        this.bindings = bindings;
        this.decrypt = decrypt;
        this.clock = clock;
    }

    public ReadPotV3Result<Link> link(ExternalIdentity eui, UUID request, RecoveryReadContext c) {
        var value = invitations.byRequest(request);
        if (value.isEmpty()) return new NotFound<>();
        var i = value.get();
        if (!i.creatorIssuer().equals(eui.issuer()) || !i.creatorSubject().equals(eui.subject()))
            return new NotFound<>();
        // Ownership check precedes decryption; no current Binding is required for the creator.
        return new Available<>(
                new Link(i.invitationId(), i.potId(), i.expiresAt(), decrypt.apply(i)),
                i.version());
    }

    public ReadPotV3Result<List<Place>> places(
            ExternalIdentity eui,
            String bindingToken,
            String invitationToken,
            RecoveryReadContext c) {
        var binding = bindings.read(eui, c);
        if (binding.isEmpty()
                || !binding.get().isBound()
                || !binding.get().bindingToken().value().equals(bindingToken))
            return new NotFound<>();
        var invitation = invitations.byHash(InvitationTokenHash.of(invitationToken));
        if (invitation.isEmpty() || !clock.get().isBefore(invitation.get().expiresAt()))
            return new NotFound<>();
        UUID potId = invitation.get().potId();
        var current = pots.currentVersion(potId, c);
        if (current.isEmpty()) return new NotReady<>();
        var root = pots.read(potId, current.getAsLong(), c);
        if (root.isEmpty()) return new NotReady<>();
        var p = root.get();
        if (p.deleted() || p.attachedActiveUserIds().contains(binding.get().userId().value()))
            return new NotFound<>();
        var free = p.shareholders().stream().filter(s -> !s.deleted() && s.isFree()).toList();
        var keys =
                free.stream()
                        .map(
                                s ->
                                        new SubobjectKey(
                                                potId,
                                                SubobjectKind.SHAREHOLDER_DETAILS,
                                                s.id(),
                                                s.detailsVersion()))
                        .toList();
        var details = objects.readAll(keys, c);
        if (!details.keySet().containsAll(keys)) return new NotReady<>();
        var places = new ArrayList<Place>();
        for (var key : keys) {
            var d = (SubobjectContent.ShareholderDetailsContent) details.get(key).content();
            if (d.value().userId().isPresent())
                throw new IllegalStateException("Inconsistent Invitation place");
            places.add(new Place(key.objectId(), d.value().name().value()));
        }
        return new Available<>(List.copyOf(places), p.version());
    }
}
