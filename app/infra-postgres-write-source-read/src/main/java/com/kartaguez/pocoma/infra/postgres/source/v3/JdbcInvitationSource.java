package com.kartaguez.pocoma.infra.postgres.source.v3;

import com.kartaguez.pocoma.domain.pot.v3.Invitation;
import com.kartaguez.pocoma.engine.materialize.pot.v3.InvitationSource;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import javax.sql.DataSource;

/**
 * Load exact protected history by published event; no live producer or encryption keys required.
 */
public final class JdbcInvitationSource implements InvitationSource {
    private final DataSource source;
    private final JdbcTemplate sql;

    public JdbcInvitationSource(DataSource source) {
        this.source = source;
        sql = new JdbcTemplate(source);
    }

    public Invitation load(UUID event, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                source,
                context,
                () -> {
                    var rows =
                            sql.query(
                                    "select i.* from v3_invitation i join v3_invitation_event e"
                                        + " using(invitation_id,version) join v3_event_position p"
                                        + " on p.event_id=e.event_id and p.family='INVITATION' join"
                                        + " v3_event_counter c on c.family=p.family and"
                                        + " c.position>=p.position where e.event_id=? and"
                                        + " e.subject_type='INVITATION'",
                                    (r, n) ->
                                            new Invitation(
                                                    r.getObject("invitation_id", UUID.class),
                                                    r.getLong("version"),
                                                    r.getObject("creation_request_id", UUID.class),
                                                    r.getString("creator_issuer"),
                                                    r.getString("creator_subject"),
                                                    r.getObject("pot_id", UUID.class),
                                                    r.getTimestamp("expires_at").toInstant(),
                                                    r.getString("token_hash"),
                                                    r.getString("protected_link")),
                                    event);
                    if (rows.size() != 1)
                        throw new IllegalStateException("Missing exact Invitation history");
                    return rows.getFirst();
                });
    }
}
