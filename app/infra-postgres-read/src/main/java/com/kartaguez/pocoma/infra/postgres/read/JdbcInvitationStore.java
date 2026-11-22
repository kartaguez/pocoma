package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.pot.invitation.Invitation;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.InvitationStore;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

public final class JdbcInvitationStore implements InvitationStore {
    private static final ReadAvailability.Family FAMILY = ReadAvailability.Family.CURRENT_INVITATION_CHANGED;

    private final ReadJdbc db;
    private final ReadAvailability availability;

    public JdbcInvitationStore(DataSource source) {
        this(new ReadJdbc(source), false);
    }

    public JdbcInvitationStore(DataSource source, SpringAttemptTransactions owner) {
        this(source, owner, true);
    }

    public JdbcInvitationStore(DataSource source, SpringAttemptTransactions owner, boolean publishAvailability) {
        this(new ReadJdbc(source, owner), publishAvailability);
    }

    private JdbcInvitationStore(ReadJdbc db, boolean publishAvailability) {
        this.db = db;
        availability = new ReadAvailability(db, publishAvailability);
    }

    private Optional<Invitation> find(String column, Object value) {
        return db.participate(() -> load(column, value));
    }

    private Optional<Invitation> load(String column, Object value) {
        return db.sql.query(
                "select * from read_current_invitation where " + column + " = ?",
                (row, index) -> new Invitation(
                        row.getObject("invitation_id", UUID.class),
                        row.getLong("version"),
                        row.getObject("creation_request_id", UUID.class),
                        row.getString("creator_issuer"),
                        row.getString("creator_subject"),
                        row.getObject("pot_id", UUID.class),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getString("token_hash"),
                        row.getString("protected_link")),
                value).stream().findFirst();
    }

    @Override
    public Optional<Invitation> byRequest(UUID id) {
        return find("creation_request_id", id);
    }

    @Override
    public Optional<Invitation> byHash(String hash) {
        return find("token_hash", hash);
    }

    @Override
    public boolean put(Invitation invitation) {
        return db.write(() -> {
            boolean advanced = db.sql.update("""
                    insert into read_current_invitation(
                        invitation_id, version, creation_request_id, creator_issuer,
                        creator_subject, pot_id, expires_at, token_hash, protected_link)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (invitation_id) do update
                    set version = excluded.version, expires_at = excluded.expires_at,
                        protected_link = excluded.protected_link
                    where read_current_invitation.version < excluded.version
                      and read_current_invitation.creation_request_id = excluded.creation_request_id
                      and read_current_invitation.creator_issuer = excluded.creator_issuer
                      and read_current_invitation.creator_subject = excluded.creator_subject
                      and read_current_invitation.pot_id = excluded.pot_id
                      and read_current_invitation.token_hash = excluded.token_hash
                    """, invitation.invitationId(), invitation.version(), invitation.creationRequestId(),
                    invitation.creatorIssuer(), invitation.creatorSubject(), invitation.potId(),
                    Timestamp.from(invitation.expiresAt()), invitation.tokenHash(), invitation.protectedLink()) == 1;
            requireActual(invitation);
            return advanced;
        });
    }

    private static boolean matchesExpected(Invitation actual, Invitation expected) {
        if (!actual.invitationId().equals(expected.invitationId())
                || !actual.creationRequestId().equals(expected.creationRequestId())
                || !actual.creatorIssuer().equals(expected.creatorIssuer())
                || !actual.creatorSubject().equals(expected.creatorSubject())
                || !actual.potId().equals(expected.potId())
                || !actual.tokenHash().equals(expected.tokenHash())) return false;
        if (actual.version() > expected.version()) return true;
        return actual.version() == expected.version() && actual.equals(expected);
    }

    private Invitation requireActual(Invitation expected) {
        var actual = load("creation_request_id", expected.creationRequestId()).orElseThrow();
        if (!matchesExpected(actual, expected)) {
            throw new IllegalStateException("Divergent Invitation projection");
        }
        return actual;
    }

    @Override
    public void requireCompletionReady(Invitation expected, boolean advanced) {
        db.write(() -> {
            var actual = requireActual(expected);
            availability.requireCurrent(FAMILY, actual.invitationId(), actual.version(), advanced);
            return null;
        });
    }

    @Override
    public ReadEvidence evidence(Invitation expected, RecoveryReadContext context) {
        return db.read(context, () -> {
            var actual = load("creation_request_id", expected.creationRequestId());
            if (!availability.currentMarkerMatches(
                    FAMILY, expected.invitationId(), actual.map(Invitation::version).orElse(null))) {
                return ReadEvidence.DIVERGENT;
            }
            if (actual.isEmpty() || actual.get().version() < expected.version()) {
                return ReadEvidence.MISSING;
            }
            return matchesExpected(actual.get(), expected) ? ReadEvidence.SATISFIED : ReadEvidence.DIVERGENT;
        });
    }

    @Override
    public void closePublication(Invitation expected, boolean advanced) {
        availability.closeAndPublish(ReadAvailability.Publication.current(
                FAMILY, expected.invitationId(), expected.version(), advanced));
    }
}
