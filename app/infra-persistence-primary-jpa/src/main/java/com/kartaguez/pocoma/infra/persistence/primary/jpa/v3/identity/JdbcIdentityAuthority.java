package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.consume.registration.v3.IdentityAuthority;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * WRITE authority primitives. Decisions belong to IdentityTransitions; the caller owns resolution.
 */
public final class JdbcIdentityAuthority implements IdentityAuthority {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    // Reuse the owner's existing resource lifetime. No resource binding, callback, or new unit.
    // Weak keys release completed holders; only the owning thread can use a live holder.
    private final Map<Object, Map<ExternalIdentity, ExternalUserIdentityId>> protections =
            Collections.synchronizedMap(new WeakHashMap<>());

    public JdbcIdentityAuthority(DataSource source, SpringAttemptTransactions transactions) {
        this.source = Objects.requireNonNull(source);
        Objects.requireNonNull(transactions).requireSource(source);
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public Map<ExternalIdentity, ExternalUserIdentityId> lockInCanonicalOrder(
            Collection<ExternalIdentity> identities) {
        return write(
                () -> {
                    // Complete validation precedes even the first INSERT, including invalid
                    // trailing keys.
                    var keys = new TreeSet<ExternalIdentity>(ExternalUserIdentity::compareKeys);
                    for (var key : Objects.requireNonNull(identities)) {
                        ExternalUserIdentity.compareKeys(key, key);
                        keys.add(key);
                    }
                    var resource = TransactionSynchronizationManager.getResource(source);
                    if (protections.containsKey(resource))
                        throw new IllegalStateException(
                                "EUI protections must be acquired in one complete set");
                    var locked = new LinkedHashMap<ExternalIdentity, ExternalUserIdentityId>();
                    for (var key : keys) {
                        jdbc.update(
                                """
                                insert into v3_eui_authority(eui_id,issuer,subject) values (?,?,?)
                                on conflict (issuer,subject) do nothing
                                """,
                                UUID.randomUUID(),
                                key.issuer(),
                                key.subject());
                        UUID id =
                                jdbc.queryForObject(
                                        """
                                        select eui_id from v3_eui_authority
                                        where issuer=? and subject=? for update
                                        """,
                                        UUID.class,
                                        key.issuer(),
                                        key.subject());
                        locked.put(key, new ExternalUserIdentityId(id));
                    }
                    var result = Collections.unmodifiableMap(locked);
                    protections.put(resource, result);
                    return result;
                });
    }

    @Override
    public Optional<ExternalUserIdentity> current(ExternalIdentity identity) {
        return write(
                () -> {
                    requireProtection(identity, null);
                    return jdbc
                            .query(
                                    """
select a.eui_id,a.issuer,a.subject,v.version,v.user_id,v.binding_token
from v3_eui_authority a join v3_eui_version v
  on v.eui_id=a.eui_id and v.version=a.current_version
where a.issuer=? and a.subject=?
""",
                                    (rs, n) -> state(rs),
                                    identity.issuer(),
                                    identity.subject())
                            .stream()
                            .findFirst();
                });
    }

    @Override
    public void apply(IdentityTransitionResult.Changed changes, UUID requestId) {
        write(
                () -> {
                    Objects.requireNonNull(requestId);
                    var state = Objects.requireNonNull(changes).identity();
                    requireProtection(state.identity(), state.id());
                    if (changes.createdUser().isPresent())
                        jdbc.update(
                                "insert into v3_pocoma_user(user_id,created_request_id) values"
                                        + " (?,?)",
                                changes.createdUser().get().id().value(),
                                requestId);
                    jdbc.update(
                            """
insert into v3_eui_version(eui_id,version,user_id,binding_token,request_id)
values (?,?,?,?,?)
""",
                            state.id().value(),
                            state.version(),
                            state.userId() == null ? null : state.userId().value(),
                            state.bindingToken() == null ? null : state.bindingToken().value(),
                            requestId);
                    if (jdbc.update(
                                    """
                                    update v3_eui_authority set current_version=?
                                    where eui_id=? and current_version=?
                                    """,
                                    state.version(),
                                    state.id().value(),
                                    state.version() - 1)
                            != 1) throw new IllegalStateException("EUI successor collision");
                    return null;
                });
    }

    @Override
    public Optional<ExternalUserIdentity> at(ExternalUserIdentityId id, long version) {
        return read(
                () -> {
                    Objects.requireNonNull(id);
                    if (version < 1)
                        throw new IllegalArgumentException("EUI version must be positive");
                    return jdbc
                            .query(
                                    """
select a.eui_id,a.issuer,a.subject,v.version,v.user_id,v.binding_token
from v3_eui_version v join v3_eui_authority a using(eui_id)
where v.eui_id=? and v.version=?
""",
                                    (rs, n) -> state(rs),
                                    id.value(),
                                    version)
                            .stream()
                            .findFirst();
                });
    }

    @Override
    public Optional<User> user(PocomaUserId id) {
        return read(
                () ->
                        jdbc
                                .query(
                                        "select user_id from v3_pocoma_user where user_id=?",
                                        (rs, n) ->
                                                new User(
                                                        new PocomaUserId(
                                                                rs.getObject(1, UUID.class))),
                                        Objects.requireNonNull(id).value())
                                .stream()
                                .findFirst());
    }

    private void requireProtection(ExternalIdentity key, ExternalUserIdentityId id) {
        ExternalUserIdentity.compareKeys(key, key);
        var locked = protections.get(TransactionSynchronizationManager.getResource(source));
        if (locked == null
                || !locked.containsKey(key)
                || (id != null && !id.equals(locked.get(key))))
            throw new IllegalStateException("EUI was not protected by this attempt");
    }

    private static ExternalUserIdentity state(ResultSet rs) throws SQLException {
        UUID user = rs.getObject("user_id", UUID.class);
        return new ExternalUserIdentity(
                new ExternalUserIdentityId(rs.getObject("eui_id", UUID.class)),
                new ExternalIdentity(rs.getString("issuer"), rs.getString("subject")),
                rs.getLong("version"),
                user == null ? null : new PocomaUserId(user),
                user == null ? null : new BindingToken(rs.getString("binding_token")));
    }

    private <T> T write(Supplier<T> action) {
        return SpringAttemptTransactions.guarded(source, () -> redacted(action));
    }

    private <T> T read(Supplier<T> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source))
            throw new IllegalStateException("Caller authority transaction required");
        return redacted(action);
    }

    private static <T> T redacted(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException failure) {
            // JDBC DETAIL may contain a binding token (notably a unique violation). Never retain
            // that exception as cause/suppressed or interpolate SQL parameters into diagnostics.
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
