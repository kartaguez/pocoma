package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;
import com.kartaguez.pocoma.infra.security.spring.v3.InvitationCryptography;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import javax.sql.DataSource;

public final class JdbcInvitationStore implements InvitationStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final InvitationCryptography crypto;

    public JdbcInvitationStore(DataSource source, InvitationCryptography crypto) {
        this.source = Objects.requireNonNull(source);
        jdbc = new JdbcTemplate(source);
        this.crypto = Objects.requireNonNull(crypto);
    }

    public Invitation prepare(PotCommand c, PotCommand.CreateInvitation intent) {
        String token = crypto.newToken();
        Instant expires = intent.expiresAt();
        var i =
                new Invitation(
                        UUID.randomUUID(),
                        1,
                        c.requestId(),
                        c.requester().issuer(),
                        c.requester().subject(),
                        c.potId().value(),
                        expires,
                        InvitationTokenHash.of(token),
                        "unsealed");
        return new Invitation(
                i.invitationId(),
                i.version(),
                i.creationRequestId(),
                i.creatorIssuer(),
                i.creatorSubject(),
                i.potId(),
                i.expiresAt(),
                i.tokenHash(),
                crypto.encrypt(i, "pocoma://invitation/" + token));
    }

    public Optional<Invitation> find(String hash) {
        return SpringAttemptTransactions.participating(
                source,
                () ->
                        jdbc
                                .query(
                                        "select * from v3_invitation where token_hash=?",
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
                                        hash)
                                .stream()
                                .findFirst());
    }

    public boolean unexpired(Instant expires) {
        return SpringAttemptTransactions.participating(
                source,
                () ->
                        Boolean.TRUE.equals(
                                jdbc.queryForObject(
                                        "select clock_timestamp() < ?",
                                        Boolean.class,
                                        Timestamp.from(expires))));
    }

    public void insert(Invitation i) {
        SpringAttemptTransactions.guarded(
                source,
                () ->
                        jdbc.update(
                                "insert into"
                                    + " v3_invitation(invitation_id,version,creation_request_id,creator_issuer,creator_subject,pot_id,expires_at,token_hash,protected_link)"
                                    + " values (?,?,?,?,?,?,?,?,?)",
                                i.invitationId(),
                                i.version(),
                                i.creationRequestId(),
                                i.creatorIssuer(),
                                i.creatorSubject(),
                                i.potId(),
                                Timestamp.from(i.expiresAt()),
                                i.tokenHash(),
                                i.protectedLink()));
    }
}
