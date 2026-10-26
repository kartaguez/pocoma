package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandObservation;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Durable business evidence, including historical candidate effects. Never opens a transaction. */
public final class JdbcPotCommandObservation implements PotCommandObservation {
    private static final List<String> KINDS =
            List.of(
                    "pot_header",
                    "pot_details",
                    "shareholder_header",
                    "shareholder_details",
                    "shareholder_weight",
                    "expense_header",
                    "expense_details",
                    "expense_financials");
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcPotCommandObservation(DataSource source) {
        this.source = Objects.requireNonNull(source);
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public RecoveryObservation observe(UUID request, RecoveryReadContext context) {
        SpringAttemptTransactions.requireRead(source, context);
        var commands = jdbc.queryForList("select * from v3_command where request_id=?", request);
        if (commands.size() != 1) return RecoveryObservation.INCONSISTENT;
        var command = commands.getFirst();
        var results =
                jdbc.queryForList("select * from v3_command_result where request_id=?", request);
        var completions =
                jdbc.queryForList(
                        "select e.event_id,p.position from v3_command_completion_event e left join"
                            + " v3_event_position p on p.family='COMMAND_COMPLETION' and"
                            + " p.event_id=e.event_id where e.subject_id=?",
                        request);
        var events =
                jdbc.queryForList(
                        "select e.*,p.position from v3_pot_event e left join v3_event_position p on"
                                + " p.family='POT' and p.event_id=e.event_id where e.request_id=?",
                        request);
        if ("PENDING".equals(command.get("status")))
            return results.isEmpty() && completions.isEmpty() && events.isEmpty()
                    ? RecoveryObservation.NOT_COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        if (!"TERMINAL".equals(command.get("status"))
                || results.size() != 1
                || completions.size() != 1
                || completions.getFirst().get("position") == null)
            return RecoveryObservation.INCONSISTENT;
        var result = results.getFirst();
        String outcome = (String) result.get("outcome");
        if ("REJECTED".equals(outcome))
            return result.get("rejection") != null
                            && result.get("resulting_pot_version") == null
                            && events.isEmpty()
                    ? RecoveryObservation.COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        if (!(result.get("resulting_pot_version") instanceof Number version)
                || version.longValue() < 1
                || result.get("rejection") != null) return RecoveryObservation.INCONSISTENT;
        UUID pot = (UUID) command.get("pot_id");
        if (jdbc.queryForObject(
                        "select count(*) from v3_pot_version where pot_id=? and version=?",
                        Long.class,
                        pot,
                        version.longValue())
                != 1) return RecoveryObservation.INCONSISTENT;
        if ("UNCHANGED".equals(outcome))
            return events.isEmpty()
                    ? RecoveryObservation.COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        if (!"CHANGED".equals(outcome) || events.size() != 1)
            return RecoveryObservation.INCONSISTENT;
        var event = events.getFirst();
        if (!pot.equals(event.get("pot_id"))
                || !Objects.equals(
                        version.longValue(), ((Number) event.get("subject_version")).longValue())
                || event.get("position") == null) return RecoveryObservation.INCONSISTENT;
        var actual = new HashSet<List<Object>>();
        for (var kind : KINDS) {
            jdbc.query(
                    "select object_id,local_version from v3_"
                            + kind
                            + " where pot_id=? and active_from=?",
                    rs -> {
                        actual.add(
                                List.of(
                                        kind.toUpperCase(Locale.ROOT),
                                        rs.getObject(1, UUID.class),
                                        rs.getLong(2)));
                    },
                    pot,
                    version.longValue());
        }
        var published = new HashSet<List<Object>>();
        jdbc.query(
                "select kind,object_id,local_version from v3_pot_event_component where event_id=?",
                rs -> {
                    published.add(
                            List.of(rs.getString(1), rs.getObject(2, UUID.class), rs.getLong(3)));
                },
                event.get("event_id"));
        return !actual.isEmpty() && actual.equals(published)
                ? RecoveryObservation.COMPLETED
                : RecoveryObservation.INCONSISTENT;
    }
}
