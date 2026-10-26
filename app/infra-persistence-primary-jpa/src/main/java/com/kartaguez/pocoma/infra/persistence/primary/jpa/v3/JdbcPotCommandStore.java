package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.domain.pot.v3.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** V3 WRITE only. Initial admission/discovery/acquisition are deliberately outside this writer. */
public final class JdbcPotCommandStore implements PotCommandStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final SpringAttemptTransactions transactions;

    public JdbcPotCommandStore(DataSource source, SpringAttemptTransactions transactions) {
        this.source = Objects.requireNonNull(source);
        this.transactions = Objects.requireNonNull(transactions);
        transactions.requireSource(source);
        jdbc = new JdbcTemplate(source);
    }

    private <T> T guarded(Supplier<T> action) {
        return SpringAttemptTransactions.guarded(source, action);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source))
            throw new IllegalStateException("Caller WRITE transaction required");
    }

    @Override
    public Stored load(UUID requestId) {
        requireTransaction();
        var rows =
                jdbc.queryForList(
                        "select c.*,r.outcome,r.resulting_pot_version,r.rejection from v3_command c"
                                + " left join v3_command_result r using(request_id) where"
                                + " c.request_id=?",
                        requestId);
        if (rows.size() != 1) throw new InconsistentPotFactsException("Durable Command missing");
        var row = rows.getFirst();
        var command =
                new PotCommand(
                        requestId,
                        new PotCommand.Eui(
                                (String) row.get("requester_issuer"),
                                (String) row.get("requester_subject")),
                        (String) row.get("binding_token"),
                        new PotId((UUID) row.get("pot_id")),
                        ((Number) row.get("observed_version")).longValue(),
                        PotCommandJson.decode(
                                (String) row.get("operation"), row.get("payload").toString()));
        boolean terminal = row.get("status").equals("TERMINAL");
        if (terminal != (row.get("outcome") != null))
            throw new InconsistentPotFactsException("Terminal/result mismatch");
        if (!terminal) return new Stored(command, Optional.empty());
        var version = (Number) row.get("resulting_pot_version");
        return new Stored(
                command,
                Optional.of(
                        new Result(
                                Outcome.valueOf((String) row.get("outcome")),
                                version == null ? null : version.longValue(),
                                (String) row.get("rejection"))));
    }

    @Override
    public long currentVersion(PotId pot) {
        requireTransaction();
        var versions =
                jdbc.queryForList(
                        "select current_version from v3_pot_identity where pot_id=?",
                        Long.class,
                        pot.value());
        return versions.isEmpty() ? 0 : versions.getFirst();
    }

    @Override
    public boolean protectPot(PotId pot, long expected) {
        return guarded(
                () -> {
                    // Also serializes negative existence. Hash collision only adds harmless
                    // contention.
                    jdbc.execute(
                            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                                    connection -> {
                                        try (var statement =
                                                connection.prepareStatement(
                                                        "select"
                                                            + " pg_advisory_xact_lock(hashtextextended(?,"
                                                            + " 731))")) {
                                            statement.setString(1, pot.toString());
                                            statement.execute();
                                            return null;
                                        }
                                    });
                    var rows =
                            jdbc.queryForList(
                                    "select current_version from v3_pot_identity where pot_id=? for"
                                            + " update",
                                    Long.class,
                                    pot.value());
                    return (rows.isEmpty() ? 0 : rows.getFirst()) == expected;
                });
    }

    @Override
    public void finishTerminal(PotCommand command, Result result) {
        guarded(
                () -> {
                    jdbc.update(
                            "insert into"
                                + " v3_command_result(request_id,outcome,resulting_pot_version,rejection)"
                                + " values (?,?,?,?)",
                            command.requestId(),
                            result.outcome().name(),
                            result.resultingPotVersion(),
                            result.rejection());
                    if (jdbc.update(
                                    "update v3_command set status='TERMINAL' where request_id=? and"
                                            + " status='PENDING'",
                                    command.requestId())
                            != 1) throw new InconsistentPotFactsException("Command not pending");
                    return null;
                });
    }

    @Override
    public void requireCompletionReady(PotCommand command, Result result, List<Component> changed) {
        guarded(
                () -> {
                    var durable = load(command.requestId());
                    if (!durable.command().equals(command)
                            || !durable.terminal().equals(Optional.of(result)))
                        throw new InconsistentPotFactsException(
                                "Completion requires the exact terminal result");
                    if (result.outcome() == Outcome.CHANGED) {
                        var actual =
                                changedComponents(command.potId(), result.resultingPotVersion());
                        if (changed.isEmpty()
                                || changed.size() != new HashSet<>(changed).size()
                                || !new HashSet<>(actual).equals(new HashSet<>(changed)))
                            throw new InconsistentPotFactsException(
                                    "Publication differs from exact persisted proposal");
                    } else if (!changed.isEmpty())
                        throw new InconsistentPotFactsException(
                                "No-effect publication has changes");
                    if (result.outcome() != Outcome.CHANGED
                            && Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "select coalesce((select"
                                                + " writer_xid=pg_current_xact_id() from"
                                                + " v3_pot_version where pot_id=? order by version"
                                                + " desc limit 1),false)",
                                            Boolean.class,
                                            command.potId().value())))
                        throw new InconsistentPotFactsException(
                                "No-effect completion has a candidate in this attempt");
                    return null;
                });
    }

    @Override
    public void publish(PotCommand command, Result result, List<Component> changed) {
        guarded(
                () -> {
                    UUID potEvent = result.outcome() == Outcome.CHANGED ? UUID.randomUUID() : null;
                    if (potEvent != null) {
                        jdbc.update(
                                "insert into"
                                    + " v3_pot_event(event_id,pot_id,subject_version,request_id,recorded_at)"
                                    + " values (?,?,?,?,clock_timestamp())",
                                potEvent,
                                command.potId().value(),
                                result.resultingPotVersion(),
                                command.requestId());
                        changed.stream()
                                .sorted(
                                        Comparator.comparing(Component::kind)
                                                .thenComparing(Component::objectId))
                                .forEach(
                                        c ->
                                                jdbc.update(
                                                        "insert into"
                                                            + " v3_pot_event_component(event_id,kind,object_id,local_version)"
                                                            + " values (?,?,?,?)",
                                                        potEvent,
                                                        c.kind(),
                                                        c.objectId(),
                                                        c.localVersion()));
                    }
                    UUID completion = UUID.randomUUID();
                    jdbc.update(
                            "insert into"
                                + " v3_command_completion_event(event_id,subject_id,recorded_at)"
                                + " values (?,?,clock_timestamp())",
                            completion,
                            command.requestId());
                    transactions.flushBeforePublication();
                    validateBusinessConstraints();
                    SpringAttemptTransactions.closePublication(source);
                    // Closed suffix. Only pre-existing family counters and new publication-position
                    // rows.
                    if (potEvent != null) position("POT", potEvent);
                    position("COMMAND_COMPLETION", completion);
                    return null;
                });
    }

    private List<Component> changedComponents(PotId pot, long version) {
        var result = new ArrayList<Component>();
        Boolean owned =
                jdbc.queryForObject(
                        "select writer_xid=pg_current_xact_id() from v3_pot_version where pot_id=?"
                                + " and version=?",
                        Boolean.class,
                        pot.value(),
                        version);
        if (!Boolean.TRUE.equals(owned))
            throw new InconsistentPotFactsException(
                    "Publication does not own candidate transaction");
        for (String kind :
                List.of(
                        "pot_header",
                        "pot_details",
                        "shareholder_header",
                        "shareholder_details",
                        "shareholder_weight",
                        "expense_header",
                        "expense_details",
                        "expense_financials")) {
            jdbc.query(
                    "select object_id,local_version from v3_"
                            + kind
                            + " where pot_id=? and active_from=?",
                    rs -> {
                        result.add(
                                new Component(
                                        kind.toUpperCase(Locale.ROOT),
                                        rs.getObject(1, UUID.class),
                                        rs.getLong(2)));
                    },
                    pot.value(),
                    version);
        }
        return List.copyOf(result);
    }

    private void validateBusinessConstraints() {
        // Keep final publication-completeness checks deferred; those are read-only at physical
        // commit.
        // Exact V1 business set (18 deferred FKs + four deferred completeness triggers).
        // V2 command/result/event publication constraints must remain deferred to physical commit.
        jdbc.execute(
                """
                set constraints
                    v3_shareholder_identity_pot_id_created_at_fkey,
                    v3_expense_identity_pot_id_created_at_fkey,
                    v3_pot_header_pot_id_active_from_fkey,
                    v3_pot_header_pot_id_active_to_fkey,
                    v3_pot_details_pot_id_active_from_fkey,
                    v3_pot_details_pot_id_active_to_fkey,
                    v3_shareholder_header_pot_id_active_from_fkey,
                    v3_shareholder_header_pot_id_active_to_fkey,
                    v3_shareholder_details_pot_id_active_from_fkey,
                    v3_shareholder_details_pot_id_active_to_fkey,
                    v3_shareholder_weight_pot_id_active_from_fkey,
                    v3_shareholder_weight_pot_id_active_to_fkey,
                    v3_expense_header_pot_id_active_from_fkey,
                    v3_expense_header_pot_id_active_to_fkey,
                    v3_expense_details_pot_id_active_from_fkey,
                    v3_expense_details_pot_id_active_to_fkey,
                    v3_expense_financials_pot_id_active_from_fkey,
                    v3_expense_financials_pot_id_active_to_fkey,
                    v3_version_has_effect,
                    v3_pot_complete,
                    v3_shareholder_complete,
                    v3_expense_complete
                immediate
                """);
    }

    private void position(String family, UUID event) {
        Long position =
                jdbc.queryForObject(
                        "update v3_event_counter set position=position+1 where family=? returning"
                                + " position",
                        Long.class,
                        family);
        if (position == null) throw new InconsistentPotFactsException("Event family missing");
        jdbc.update(
                "insert into v3_event_position(family,position,event_id) values (?,?,?)",
                family,
                position,
                event);
    }
}
