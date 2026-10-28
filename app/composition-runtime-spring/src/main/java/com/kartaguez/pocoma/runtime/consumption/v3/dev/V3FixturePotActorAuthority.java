package com.kartaguez.pocoma.runtime.consumption.v3.dev;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Single explicitly installed EUI fixture, not the L3 Identity/EUI authority. */
public final class V3FixturePotActorAuthority implements PotActorAuthority {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public V3FixturePotActorAuthority(DataSource source) {
        this.source = Objects.requireNonNull(source);
        jdbc = new JdbcTemplate(source);
    }

    private Witness read(PotCommand command, boolean lock) {
        if (!command.requester().issuer().equals("i5-dev")
                || !command.requester().subject().equals("actor"))
            throw new IllegalArgumentException("EUI outside the authorized development fixture");
        var rows =
                jdbc.query(
                        "select revision,user_id,binding_token from i5_fixture_identity where"
                                + " issuer=? and subject=?"
                                + (lock ? " for update" : ""),
                        (r, n) ->
                                new Witness(
                                        r.getLong(1),
                                        command.bindingToken().equals(r.getString(3))
                                                ? Optional.ofNullable(r.getObject(2, UUID.class))
                                                        .map(UserId::new)
                                                : Optional.empty()),
                        command.requester().issuer(),
                        command.requester().subject());
        if (rows.size() != 1)
            throw new IllegalStateException("missing development Identity witness");
        return rows.getFirst();
    }

    public Witness observe(PotCommand command) {
        return read(command, false);
    }

    public boolean protect(PotCommand command, Witness observed) {
        return SpringAttemptTransactions.guarded(
                source, () -> read(command, true).equals(observed));
    }
}
