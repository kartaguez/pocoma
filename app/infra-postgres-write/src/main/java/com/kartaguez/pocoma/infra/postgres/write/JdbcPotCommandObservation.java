package com.kartaguez.pocoma.infra.postgres.write;

import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandObservation;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

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

    private record CommandEvidence(
            Map<String, Object> command,
            List<Map<String, Object>> results,
            List<Map<String, Object>> completions,
            List<Map<String, Object>> events,
            long invitationEffects) {}

    @Override
    public RecoveryObservation observe(UUID request, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                source, context, () -> evaluateObservation(request));
    }

    private RecoveryObservation evaluateObservation(UUID request) {
        var commands = jdbc.queryForList("select * from v3_command where request_id=?", request);
        if (commands.size() != 1) return RecoveryObservation.INCONSISTENT;
        var evidence = loadEvidence(request, commands.getFirst());
        if ("PENDING".equals(evidence.command().get("status"))) {
            return isEmptyPending(evidence)
                    ? RecoveryObservation.NOT_COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        }
        if (!isValidTerminalCompletion(evidence)) return RecoveryObservation.INCONSISTENT;
        var result = evidence.results().getFirst();
        String outcome = (String) result.get("outcome");
        if ("REJECTED".equals(outcome)) {
            return isValidRejected(result, evidence)
                    ? RecoveryObservation.COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        }
        if ("INVITATION_CREATED".equals(outcome)) {
            return evaluateInvitationCreated(request, evidence, result);
        }
        return evaluateVersionedOutcome(evidence, result, outcome);
    }

    private CommandEvidence loadEvidence(UUID request, Map<String, Object> command) {
        var results =
                jdbc.queryForList("select * from v3_command_result where request_id=?", request);
        var completions =
                jdbc.queryForList(
                        "select e.event_id,p.position from v3_command_completion_event"
                                + " e left join v3_event_position p on"
                                + " p.family='COMMAND_COMPLETION' and p.event_id=e.event_id"
                                + " where e.subject_id=?",
                        request);
        var events =
                jdbc.queryForList(
                        "select e.*,p.position from v3_pot_event e left join"
                                + " v3_event_position p on p.family='POT' and"
                                + " p.event_id=e.event_id where e.request_id=?",
                        request);
        long invitationEffects =
                "CreateInvitation".equals(command.get("operation"))
                        ? jdbc.queryForObject(
                                "select count(*) from v3_invitation where creation_request_id=?",
                                Long.class,
                                request)
                        : 0;
        return new CommandEvidence(command, results, completions, events, invitationEffects);
    }

    private static boolean isEmptyPending(CommandEvidence evidence) {
        return evidence.results().isEmpty()
                && evidence.completions().isEmpty()
                && evidence.events().isEmpty()
                && evidence.invitationEffects() == 0;
    }

    private static boolean isValidTerminalCompletion(CommandEvidence evidence) {
        if (!"TERMINAL".equals(evidence.command().get("status"))) return false;
        if (evidence.results().size() != 1 || evidence.completions().size() != 1) return false;
        return evidence.completions().getFirst().get("position") != null;
    }

    private static boolean isValidRejected(
            Map<String, Object> result, CommandEvidence evidence) {
        if (result.get("rejection") == null || result.get("resulting_pot_version") != null) {
            return false;
        }
        return evidence.events().isEmpty() && evidence.invitationEffects() == 0;
    }

    private RecoveryObservation evaluateInvitationCreated(
            UUID request, CommandEvidence evidence, Map<String, Object> result) {
        var command = evidence.command();
        if (!"CreateInvitation".equals(command.get("operation")) || !evidence.events().isEmpty()) {
            return RecoveryObservation.INCONSISTENT;
        }
        if (result.get("resulting_pot_version") != null || result.get("rejection") != null) {
            return RecoveryObservation.INCONSISTENT;
        }
        Integer count =
                jdbc.queryForObject(
                        "select count(*) from v3_invitation i join v3_invitation_event e"
                                + " using(invitation_id,version) join v3_event_position p on"
                                + " p.event_id=e.event_id and p.family='INVITATION' join"
                                + " v3_event_counter counter on counter.family=p.family and"
                                + " counter.position>=p.position where i.creation_request_id=?"
                                + " and i.pot_id=? and i.creator_issuer=? and i.creator_subject=?",
                        Integer.class,
                        request,
                        command.get("pot_id"),
                        command.get("requester_issuer"),
                        command.get("requester_subject"));
        return count != null && count == 1
                ? RecoveryObservation.COMPLETED
                : RecoveryObservation.INCONSISTENT;
    }

    private RecoveryObservation evaluateVersionedOutcome(
            CommandEvidence evidence, Map<String, Object> result, String outcome) {
        if (result.get("rejection") != null) return RecoveryObservation.INCONSISTENT;
        if (!(result.get("resulting_pot_version") instanceof Number version)
                || version.longValue() < 1) {
            return RecoveryObservation.INCONSISTENT;
        }
        UUID pot = (UUID) evidence.command().get("pot_id");
        if (!potVersionExists(pot, version.longValue())) return RecoveryObservation.INCONSISTENT;
        if ("UNCHANGED".equals(outcome)) {
            return evidence.events().isEmpty()
                    ? RecoveryObservation.COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        }
        if (!"CHANGED".equals(outcome) || evidence.events().size() != 1) {
            return RecoveryObservation.INCONSISTENT;
        }
        return evaluateChangedComponents(pot, version.longValue(), evidence.events().getFirst());
    }

    private boolean potVersionExists(UUID pot, long version) {
        Long count =
                jdbc.queryForObject(
                        "select count(*) from v3_pot_version where pot_id=? and version=?",
                        Long.class,
                        pot,
                        version);
        return count != null && count == 1;
    }

    private RecoveryObservation evaluateChangedComponents(
            UUID pot, long version, Map<String, Object> event) {
        if (!matchesChangedEvent(pot, version, event)) return RecoveryObservation.INCONSISTENT;
        var actual = queryActualComponents(pot, version);
        var published = queryPublishedComponents(event.get("event_id"));
        return !actual.isEmpty() && actual.equals(published)
                ? RecoveryObservation.COMPLETED
                : RecoveryObservation.INCONSISTENT;
    }

    private static boolean matchesChangedEvent(
            UUID pot, long version, Map<String, Object> event) {
        if (!pot.equals(event.get("pot_id")) || event.get("position") == null) return false;
        return Objects.equals(version, ((Number) event.get("subject_version")).longValue());
    }

    private Set<List<Object>> queryActualComponents(UUID pot, long version) {
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
                    version);
        }
        return actual;
    }

    private Set<List<Object>> queryPublishedComponents(Object eventId) {
        var published = new HashSet<List<Object>>();
        jdbc.query(
                "select kind,object_id,local_version from v3_pot_event_component where event_id=?",
                rs -> {
                    published.add(
                            List.of(
                                    rs.getString(1),
                                    rs.getObject(2, UUID.class),
                                    rs.getLong(3)));
                },
                eventId);
        return published;
    }
}
