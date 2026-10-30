package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Caller-owned WRITE unit. Admission and execution use distinct PostgreSQL principals. */
public final class JdbcIdentityRequestStore implements IdentityRequestStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final SpringAttemptTransactions transactions;

    public JdbcIdentityRequestStore(DataSource source, SpringAttemptTransactions transactions) {
        this.source = Objects.requireNonNull(source);
        this.transactions = Objects.requireNonNull(transactions);
        transactions.requireSource(source);
        jdbc = new JdbcTemplate(source);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source))
            throw new IllegalStateException("Caller Identity transaction required");
    }

    private <T> T write(Supplier<T> action) {
        return SpringAttemptTransactions.guarded(source, () -> redacted(action));
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
                    "Identity request persistence failure; SQLSTATE=" + state,
                    new SQLException("Redacted Identity failure", state));
        }
    }

    @Override
    public Optional<Stored> load(UUID id) {
        requireTransaction();
        return redacted(
                () ->
                        jdbc
                                .query(
                                        """
select c.*,r.outcome,r.target_eui_id,r.target_version,r.rejection
from v3_identity_request c left join v3_identity_request_result r using(request_id) where c.request_id=?
""",
                                        (r, n) -> {
                                            var requester =
                                                    new ExternalIdentity(
                                                            r.getString("requester_issuer"),
                                                            r.getString("requester_subject"));
                                            var target =
                                                    new ExternalIdentity(
                                                            r.getString("target_issuer"),
                                                            r.getString("target_subject"));
                                            var token = r.getString("binding_token");
                                            var request =
                                                    new IdentityRequest(
                                                            id,
                                                            requester,
                                                            IdentityRequestJson.operation(
                                                                    r.getString("operation"),
                                                                    target),
                                                            token == null
                                                                    ? null
                                                                    : new BindingToken(token),
                                                            r.getTimestamp("created_at")
                                                                    .toInstant(),
                                                            IdentityRequestJson.decode(
                                                                    r.getString(
                                                                            "admission_context")));
                                            String outcome = r.getString("outcome");
                                            var result =
                                                    outcome == null
                                                            ? Optional.<Result>empty()
                                                            : Optional.of(
                                                                    new Result(
                                                                            Outcome.valueOf(
                                                                                    outcome),
                                                                            r.getObject(
                                                                                                    "target_eui_id")
                                                                                            == null
                                                                                    ? null
                                                                                    : new ExternalUserIdentityId(
                                                                                            r
                                                                                                    .getObject(
                                                                                                            "target_eui_id",
                                                                                                            UUID.class)),
                                                                            (Long)
                                                                                    r.getObject(
                                                                                            "target_version"),
                                                                            r.getString("rejection")
                                                                                            == null
                                                                                    ? null
                                                                                    : IdentityTransitionResult
                                                                                            .Rejection
                                                                                            .valueOf(
                                                                                                    r
                                                                                                            .getString(
                                                                                                                    "rejection"))));
                                            return new Stored(
                                                    request,
                                                    Status.valueOf(r.getString("status")),
                                                    result);
                                        },
                                        id)
                                .stream()
                                .findFirst());
    }

    @Override
    public Stored insertPending(IdentityRequest request) {
        return write(
                () -> {
                    if (request.admission().origin() != IdentityRequest.Origin.CONTROLLED_INTERNAL)
                        throw new IllegalArgumentException(
                                "I3 admits controlled internal requests only");
                    if (request.createdAt().getNano() % 1000 != 0)
                        throw new IllegalArgumentException(
                                "PostgreSQL microsecond precision required");
                    jdbc.update(
                            """
insert into v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,
    target_issuer,target_subject,admission_context,created_at)
values (?,?,?,?,?,?,?,?::jsonb,?) on conflict(request_id) do nothing
""",
                            request.requestId(),
                            request.requesterEui().issuer(),
                            request.requesterEui().subject(),
                            IdentityRequestJson.operation(request),
                            request.bindingToken() == null ? null : request.bindingToken().value(),
                            request.target().issuer(),
                            request.target().subject(),
                            IdentityRequestJson.encode(request.admission()),
                            Timestamp.from(request.createdAt()));
                    var stored = load(request.requestId()).orElseThrow();
                    if (!stored.request().equals(request))
                        throw new IllegalStateException(
                                "Identity request ID has another intention");
                    return stored;
                });
    }

    @Override
    public void finishTerminal(IdentityRequest request, Result result) {
        write(
                () -> {
                    var durable = load(request.requestId()).orElseThrow();
                    if (durable.status() != Status.PENDING || !durable.request().equals(request))
                        throw new IllegalStateException("Identity request not pending");
                    jdbc.update(
                            "insert into v3_identity_request_result values (?,?,?,?,?)",
                            request.requestId(),
                            result.outcome().name(),
                            result.targetId() == null ? null : result.targetId().value(),
                            result.resultingVersion(),
                            result.rejection() == null ? null : result.rejection().name());
                    if (jdbc.update(
                                    "update v3_identity_request set status='TERMINAL' where"
                                            + " request_id=? and status='PENDING'",
                                    request.requestId())
                            != 1)
                        throw new IllegalStateException("Identity request terminal collision");
                    return null;
                });
    }

    @Override
    public void requireCompletionReady(
            IdentityRequest request, IdentityTransitionResult transition) {
        write(
                () -> {
                    var durable = load(request.requestId()).orElseThrow();
                    if (!durable.request().equals(request)
                            || !durable.terminal().equals(Optional.of(Result.from(transition))))
                        throw new IllegalStateException(
                                "Identity completion differs from actual result");
                    var versions =
                            jdbc.queryForList(
                                    "select"
                                        + " eui_id,version,user_id,binding_token,writer_xid=pg_current_xact_id()"
                                        + " owned from v3_eui_version where request_id=?",
                                    request.requestId());
                    var users =
                            jdbc.queryForList(
                                    "select user_id,writer_xid=pg_current_xact_id() owned from"
                                            + " v3_pocoma_user where created_request_id=?",
                                    request.requestId());
                    if (transition instanceof IdentityTransitionResult.Changed changed) {
                        var state = changed.identity();
                        if (versions.size() != 1)
                            throw new IllegalStateException("Identity effect cardinality mismatch");
                        var v = versions.getFirst();
                        if (!Boolean.TRUE.equals(v.get("owned"))
                                || !state.id().value().equals(v.get("eui_id"))
                                || !Objects.equals(state.version(), v.get("version"))
                                || !Objects.equals(
                                        state.userId() == null ? null : state.userId().value(),
                                        v.get("user_id"))
                                || !Objects.equals(
                                        state.bindingToken() == null
                                                ? null
                                                : state.bindingToken().value(),
                                        v.get("binding_token")))
                            throw new IllegalStateException("Identity effect provenance mismatch");
                        if (changed.createdUser().isPresent()
                                ? users.size() != 1
                                        || !Boolean.TRUE.equals(users.getFirst().get("owned"))
                                        || !changed.createdUser()
                                                .get()
                                                .id()
                                                .value()
                                                .equals(users.getFirst().get("user_id"))
                                : !users.isEmpty())
                            throw new IllegalStateException("Identity User provenance mismatch");
                    } else if (!versions.isEmpty() || !users.isEmpty())
                        throw new IllegalStateException("Identity rejection has effects");
                    return null;
                });
    }

    @Override
    public void publish(IdentityRequest request, IdentityTransitionResult transition) {
        write(
                () -> {
                    requireCompletionReady(request, transition);
                    var events = new ArrayList<UUID>();
                    if (transition instanceof IdentityTransitionResult.Changed changed) {
                        changed.createdUser()
                                .ifPresent(
                                        u ->
                                                events.add(
                                                        event(
                                                                request,
                                                                "POCOMA_USER",
                                                                u.id().value(),
                                                                1)));
                        events.add(
                                event(
                                        request,
                                        "EXTERNAL_USER_IDENTITY",
                                        changed.identity().id().value(),
                                        changed.identity().version()));
                    }
                    UUID completion = UUID.randomUUID();
                    jdbc.update(
                            "insert into v3_identity_completion_event values"
                                    + " (?,?,?,clock_timestamp())",
                            completion,
                            IdentityRequestJson.operation(request),
                            request.requestId());
                    transactions.flushBeforePublication();
                    jdbc.execute(
                            "set constraints v3_identity_anchor_state,v3_identity_version_state"
                                    + " immediate");
                    jdbc.queryForObject(
                            "select v3_identity_check_unit(?,false)",
                            Object.class,
                            request.requestId());
                    SpringAttemptTransactions.closePublication(source);
                    if (!events.isEmpty()) position("IDENTITY", events);
                    position("IDENTITY_COMPLETION", List.of(completion));
                    return null;
                });
    }

    private UUID event(IdentityRequest request, String type, UUID subject, long version) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into v3_identity_event values (?,?,?,?,?,clock_timestamp())",
                id,
                type,
                subject,
                version,
                request.requestId());
        return id;
    }

    private void position(String family, List<UUID> events) {
        Long end =
                jdbc.queryForObject(
                        "update v3_event_counter set position=position+? where family=? returning"
                                + " position",
                        Long.class,
                        events.size(),
                        family);
        if (end == null) throw new IllegalStateException("Identity publication counter missing");
        for (int i = 0; i < events.size(); i++)
            jdbc.update(
                    "insert into v3_event_position values (?,?,?)",
                    family,
                    end - events.size() + 1 + i,
                    events.get(i));
    }
}
