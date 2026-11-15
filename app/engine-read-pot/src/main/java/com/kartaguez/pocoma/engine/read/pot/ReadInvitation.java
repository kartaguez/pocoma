package com.kartaguez.pocoma.engine.read.pot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import com.kartaguez.pocoma.contract.readquery.InvitationRead.Link;
import com.kartaguez.pocoma.contract.readquery.InvitationRead.Place;
import com.kartaguez.pocoma.contract.readquery.PotRead;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult.Available;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult.NotFound;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult.NotReady;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.pot.Invitation;
import com.kartaguez.pocoma.domain.pot.InvitationTokenHash;
import com.kartaguez.pocoma.domain.projection.SubobjectContent;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.SubobjectKind;
import com.kartaguez.pocoma.port.projection.PotVersionStore;
import com.kartaguez.pocoma.port.projection.SubobjectVersionStore;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

/** Restricted Invitation queries; no authority or fallback in WRITE. */
public final class ReadInvitation {






    private final com.kartaguez.pocoma.port.projection.InvitationStore invitations;
    private final PotVersionStore pots;
    private final SubobjectVersionStore objects;
    private final PotRead.BindingReader bindings;
    private final Function<Invitation, String> decrypt;
    private final Supplier<Instant> clock;

    public ReadInvitation(
            com.kartaguez.pocoma.port.projection.InvitationStore invitations,
            PotVersionStore pots,
            SubobjectVersionStore objects,
            PotRead.BindingReader bindings,
            Function<Invitation, String> decrypt,
            Supplier<Instant> clock) {
        this.invitations = invitations;
        this.pots = pots;
        this.objects = objects;
        this.bindings = bindings;
        this.decrypt = decrypt;
        this.clock = clock;
    }

    public ReadPotResult<Link> link(ExternalIdentity eui, UUID request, RecoveryReadContext c) {
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

    public ReadPotResult<List<Place>> places(
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
