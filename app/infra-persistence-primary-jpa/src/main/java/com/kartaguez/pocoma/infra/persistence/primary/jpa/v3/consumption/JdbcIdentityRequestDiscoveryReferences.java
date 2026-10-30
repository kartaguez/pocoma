package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily;
import com.kartaguez.pocoma.engine.consumption.v3.DiscoveryEngine.Settings;
import com.kartaguez.pocoma.engine.consumption.v3.port.BurnableReferences;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** IdentityRequest references only; no intention, claim or lifecycle selection. */
public final class JdbcIdentityRequestDiscoveryReferences implements BurnableReferences {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final Settings settings;

    public JdbcIdentityRequestDiscoveryReferences(DataSource source, Settings settings) {
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.settings = Objects.requireNonNull(settings);
    }

    public ConsumableFamily family() {
        return ConsumableFamily.IDENTITY_REQUEST;
    }

    private void read(RecoveryReadContext context) {
        SpringAttemptTransactions.requireRead(source, context);
        jdbc.queryForObject(
                "select set_config('lock_timeout',?,true)",
                String.class,
                settings.lockTimeout().toMillis() + "ms");
        jdbc.queryForObject(
                "select set_config('statement_timeout',?,true)",
                String.class,
                settings.statementTimeout().toMillis() + "ms");
    }

    public Optional<UUID> upperPending(RecoveryReadContext context) {
        read(context);
        return jdbc
                .query(
                        "select request_id from v3_identity_request where status='PENDING' order by"
                                + " request_id desc limit 1",
                        (r, n) -> r.getObject(1, UUID.class))
                .stream()
                .findFirst();
    }

    public List<UUID> pendingPage(
            Optional<UUID> after, UUID upper, int size, RecoveryReadContext context) {
        read(context);
        Objects.requireNonNull(after);
        Objects.requireNonNull(upper);
        if (size < 1 || size > settings.pageSize())
            throw new IllegalArgumentException("invalid page bound");
        return after.isEmpty()
                ? jdbc.query(
                        "select request_id from v3_identity_request where status='PENDING' and"
                                + " request_id<=? order by request_id limit ?",
                        (r, n) -> r.getObject(1, UUID.class),
                        upper,
                        size)
                : jdbc.query(
                        "select request_id from v3_identity_request where status='PENDING' and"
                                + " request_id>? and request_id<=? order by request_id limit ?",
                        (r, n) -> r.getObject(1, UUID.class),
                        after.orElseThrow(),
                        upper,
                        size);
    }

    public boolean tryProtectPending(UUID id) {
        return SpringAttemptTransactions.guarded(
                source,
                () ->
                        !jdbc.queryForList(
                                        "select request_id from v3_identity_request where"
                                            + " request_id=? and status='PENDING' for update skip"
                                            + " locked",
                                        Objects.requireNonNull(id))
                                .isEmpty());
    }
}
