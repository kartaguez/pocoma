package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;

import com.kartaguez.pocoma.domain.pot.v3.Invitation;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.v3.InvitationStore;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.*;

import javax.sql.DataSource;

public final class JdbcInvitationStore implements InvitationStore {
    private final DataSource source;
    private final JdbcTemplate sql;

    public JdbcInvitationStore(DataSource source) {
        this.source = source;
        sql = new JdbcTemplate(source);
    }

    private Optional<Invitation> find(String column, Object value) {
        return SpringAttemptTransactions.participating(
                source,
                () ->
                        sql
                                .query(
                                        "select * from read_current_invitation where "
                                                + column
                                                + "=?",
                                        (r, n) ->
                                                new Invitation(
                                                        r.getObject("invitation_id", UUID.class),
                                                        r.getLong("version"),
                                                        r.getObject(
                                                                "creation_request_id", UUID.class),
                                                        r.getString("creator_issuer"),
                                                        r.getString("creator_subject"),
                                                        r.getObject("pot_id", UUID.class),
                                                        r.getTimestamp("expires_at").toInstant(),
                                                        r.getString("token_hash"),
                                                        r.getString("protected_link")),
                                        value)
                                .stream()
                                .findFirst());
    }

    public Optional<Invitation> byRequest(UUID id) {
        return find("creation_request_id", id);
    }

    public Optional<Invitation> byHash(String hash) {
        return find("token_hash", hash);
    }

    public void put(Invitation i) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    sql.update(
                            "insert into"
                                + " read_current_invitation(invitation_id,version,creation_request_id,creator_issuer,creator_subject,pot_id,expires_at,token_hash,protected_link)"
                                + " values (?,?,?,?,?,?,?,?,?) on conflict(invitation_id) do update"
                                + " set version=excluded.version,expires_at=excluded.expires_at,protected_link=excluded.protected_link"
                                + " where read_current_invitation.version<excluded.version and"
                                + " read_current_invitation.creation_request_id=excluded.creation_request_id"
                                + " and read_current_invitation.creator_issuer=excluded.creator_issuer"
                                + " and read_current_invitation.creator_subject=excluded.creator_subject"
                                + " and read_current_invitation.pot_id=excluded.pot_id and"
                                + " read_current_invitation.token_hash=excluded.token_hash",
                            i.invitationId(),
                            i.version(),
                            i.creationRequestId(),
                            i.creatorIssuer(),
                            i.creatorSubject(),
                            i.potId(),
                            Timestamp.from(i.expiresAt()),
                            i.tokenHash(),
                            i.protectedLink());
                    var actual = byRequest(i.creationRequestId()).orElseThrow();
                    if (!actual.invitationId().equals(i.invitationId())
                            || actual.version() < i.version()
                            || actual.version() == i.version() && !actual.equals(i))
                        throw new IllegalStateException("Divergent Invitation projection");
                    return null;
                });
    }

    public void closePublication() {
        SpringAttemptTransactions.closePublication(source);
    }
}
