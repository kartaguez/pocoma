package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.ExternalUserIdentity;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotActorAuthority;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Exact WRITE Identity witness for Pot; the caller owns both physical transactions. */
public final class JdbcIdentityPotActorAuthority implements PotActorAuthority {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final JdbcIdentityAuthority identity;

    public JdbcIdentityPotActorAuthority(
            DataSource source, SpringAttemptTransactions transactions) {
        this.source = Objects.requireNonNull(source);
        Objects.requireNonNull(transactions).requireSource(source);
        jdbc = new JdbcTemplate(source);
        identity = new JdbcIdentityAuthority(source, transactions);
    }

    @Override
    public Witness observe(PotCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        TransactionDefinition.ISOLATION_REPEATABLE_READ))
            throw new IllegalStateException("Identity observation requires Business RR RO");
        var key = key(command);
        return redacted(
                () ->
                        jdbc
                                .query(
                                        """
select a.current_version,v.version,v.user_id,v.binding_token
from v3_eui_authority a left join v3_eui_version v
  on v.eui_id=a.eui_id and v.version=a.current_version
where a.issuer=? and a.subject=?
""",
                                        (rs, n) -> {
                                            long version = rs.getLong("current_version");
                                            Long stateVersion = rs.getObject("version", Long.class);
                                            if (version > 0 && stateVersion == null)
                                                throw new IllegalStateException(
                                                        "Identity current state missing");
                                            return witness(
                                                    version,
                                                    rs.getObject("user_id", UUID.class),
                                                    rs.getString("binding_token"),
                                                    command);
                                        },
                                        key.issuer(),
                                        key.subject())
                                .stream()
                                .findFirst()
                                .orElseGet(() -> new Witness(0, Optional.empty())));
    }

    @Override
    public boolean protect(PotCommand command, Witness observed) {
        return SpringAttemptTransactions.guarded(
                source,
                () ->
                        redacted(
                                () -> {
                                    var key = key(command);
                                    Objects.requireNonNull(observed);
                                    identity.lockInCanonicalOrder(List.of(key));
                                    var state = identity.current(key);
                                    var current =
                                            state.map(
                                                            e ->
                                                                    witness(
                                                                            e.version(),
                                                                            e.userId() == null
                                                                                    ? null
                                                                                    : e.userId()
                                                                                            .value(),
                                                                            e.bindingToken() == null
                                                                                    ? null
                                                                                    : e.bindingToken()
                                                                                            .value(),
                                                                            command))
                                                    .orElseGet(
                                                            () -> new Witness(0, Optional.empty()));
                                    // A Command may create a zero anchor without publishing
                                    // Identity.
                                    // Drain its structural checks before the closed Command suffix.
                                    jdbc.execute(
                                            "set constraints v3_identity_anchor_state,"
                                                    + "v3_identity_version_state immediate");
                                    return current.equals(observed);
                                }));
    }

    private static ExternalIdentity key(PotCommand command) {
        Objects.requireNonNull(command);
        var key = new ExternalIdentity(command.requester().issuer(), command.requester().subject());
        ExternalUserIdentity.compareKeys(key, key);
        return key;
    }

    private static Witness witness(long version, UUID user, String token, PotCommand command) {
        if ((user == null) != (token == null) || (version == 0 && user != null))
            throw new IllegalStateException("Inconsistent Identity state");
        return new Witness(
                version,
                user != null && token.equals(command.bindingToken())
                        ? Optional.of(new UserId(user))
                        : Optional.empty());
    }

    private static <T> T redacted(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException failure) {
            String state =
                    failure.getMostSpecificCause() instanceof SQLException sql
                            ? sql.getSQLState()
                            : "unavailable";
            throw new IllegalStateException(
                    "Identity persistence failure; SQLSTATE=" + state,
                    new SQLException("Redacted Identity failure", state));
        }
    }
}
