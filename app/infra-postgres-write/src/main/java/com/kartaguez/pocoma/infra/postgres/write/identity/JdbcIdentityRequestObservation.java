package com.kartaguez.pocoma.infra.postgres.write.identity;

import com.kartaguez.pocoma.engine.write.identity.IdentityRequestObservation;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** R9 snapshot only: never calls the write-unit checker (its writer XID is attempt-local). */
public final class JdbcIdentityRequestObservation implements IdentityRequestObservation {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcIdentityRequestObservation(DataSource source) {
        this.source = Objects.requireNonNull(source);
        jdbc = new JdbcTemplate(source);
    }

    private record Evidence(
            Map<String, Object> request,
            List<Map<String, Object>> results,
            List<Map<String, Object>> users,
            List<Map<String, Object>> states,
            List<Map<String, Object>> events,
            List<Map<String, Object>> completions) {}

    @Override
    public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                source, context, () -> evaluateObservation(id));
    }

    private RecoveryObservation evaluateObservation(UUID id) {
        var requests = jdbc.queryForList("select * from v3_identity_request where request_id=?", id);
        if (requests.size() != 1) return RecoveryObservation.INCONSISTENT;
        var requestRow = requests.getFirst();
        if (!hasValidAdmissionProvenance(requestRow)) return RecoveryObservation.INCONSISTENT;
        var evidence = loadEvidence(id, requestRow);
        if ("PENDING".equals(requestRow.get("status"))) {
            return isEmptyPendingEvidence(evidence)
                    ? RecoveryObservation.NOT_COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        }
        if (!isValidTerminalCompletion(evidence)) return RecoveryObservation.INCONSISTENT;
        var resultRow = evidence.results().getFirst();
        if ("REJECTED".equals(resultRow.get("outcome"))) {
            return isValidRejectedEvidence(resultRow, evidence)
                    ? RecoveryObservation.COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        }
        return evaluateChangedEvidence(evidence, resultRow);
    }

    private static boolean hasValidAdmissionProvenance(Map<String, Object> requestRow) {
        // Decode provenance in this same snapshot; corrupted durable input is not rollback evidence.
        try {
            var admission =
                    IdentityRequestJson.decode(requestRow.get("admission_context").toString());
            if (!admission.initiator().issuer().equals(requestRow.get("requester_issuer"))) {
                return false;
            }
            if (!admission.initiator().subject().equals(requestRow.get("requester_subject"))) {
                return false;
            }
            return admission.target().issuer().equals(requestRow.get("target_issuer"))
                    && admission.target().subject().equals(requestRow.get("target_subject"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private Evidence loadEvidence(UUID id, Map<String, Object> requestRow) {
        var results =
                jdbc.queryForList("select * from v3_identity_request_result where request_id=?", id);
        var users =
                jdbc.queryForList("select * from v3_pocoma_user where created_request_id=?", id);
        var states =
                jdbc.queryForList(
                        "select v.*,a.issuer,a.subject from v3_eui_version v join v3_eui_authority a"
                                + " using(eui_id) where v.request_id=?",
                        id);
        var events =
                jdbc.queryForList(
                        "select e.*,p.position,p.family from v3_identity_event e left join"
                                + " v3_event_position p using(event_id) where e.request_id=?",
                        id);
        var completions =
                jdbc.queryForList(
                        "select e.*,p.position,p.family from v3_identity_completion_event e left"
                                + " join v3_event_position p using(event_id) where e.subject_id=?",
                        id);
        return new Evidence(requestRow, results, users, states, events, completions);
    }

    private static boolean isEmptyPendingEvidence(Evidence evidence) {
        return evidence.results().isEmpty()
                && evidence.users().isEmpty()
                && evidence.states().isEmpty()
                && evidence.events().isEmpty()
                && evidence.completions().isEmpty();
    }

    private static boolean isValidTerminalCompletion(Evidence evidence) {
        if (!"TERMINAL".equals(evidence.request().get("status"))) return false;
        if (evidence.results().size() != 1 || evidence.completions().size() != 1) return false;
        var completion = evidence.completions().getFirst();
        return Objects.equals(evidence.request().get("operation"), completion.get("subject_type"))
                && positioned(completion, "IDENTITY_COMPLETION");
    }

    private static boolean isValidRejectedEvidence(
            Map<String, Object> resultRow, Evidence evidence) {
        if (resultRow.get("rejection") == null) return false;
        if (resultRow.get("target_eui_id") != null || resultRow.get("target_version") != null) {
            return false;
        }
        return evidence.users().isEmpty()
                && evidence.states().isEmpty()
                && evidence.events().isEmpty();
    }

    private RecoveryObservation evaluateChangedEvidence(
            Evidence evidence, Map<String, Object> resultRow) {
        if (!"CHANGED".equals(resultRow.get("outcome")) || resultRow.get("rejection") != null) {
            return RecoveryObservation.INCONSISTENT;
        }
        if (evidence.states().size() != 1) return RecoveryObservation.INCONSISTENT;
        var versionRow = evidence.states().getFirst();
        if (!matchesChangedState(evidence.request(), resultRow, versionRow)) {
            return RecoveryObservation.INCONSISTENT;
        }
        if (!hasValidPredecessorVersion(versionRow)) return RecoveryObservation.INCONSISTENT;
        var euiEvents = eventsOfType(evidence.events(), "EXTERNAL_USER_IDENTITY");
        if (!hasValidEuiEvent(euiEvents, versionRow)) return RecoveryObservation.INCONSISTENT;
        return hasValidOperationEffects(evidence, versionRow, euiEvents.getFirst())
                ? RecoveryObservation.COMPLETED
                : RecoveryObservation.INCONSISTENT;
    }

    private static boolean matchesChangedState(
            Map<String, Object> requestRow,
            Map<String, Object> resultRow,
            Map<String, Object> versionRow) {
        if (!Objects.equals(resultRow.get("target_eui_id"), versionRow.get("eui_id"))) return false;
        if (!Objects.equals(resultRow.get("target_version"), versionRow.get("version"))) {
            return false;
        }
        if (!Objects.equals(requestRow.get("target_issuer"), versionRow.get("issuer"))) return false;
        if (!Objects.equals(requestRow.get("target_subject"), versionRow.get("subject"))) {
            return false;
        }
        boolean tokenNull = versionRow.get("binding_token") == null;
        boolean userNull = versionRow.get("user_id") == null;
        return "UNLINK".equals(requestRow.get("operation")) == userNull && userNull == tokenNull;
    }

    private boolean hasValidPredecessorVersion(Map<String, Object> versionRow) {
        long version = ((Number) versionRow.get("version")).longValue();
        if (version < 1) return false;
        if (version == 1) return true;
        Long count =
                jdbc.queryForObject(
                        "select count(*) from v3_eui_version where eui_id=? and version=? and"
                                + " (user_id is null)<>?",
                        Long.class,
                        versionRow.get("eui_id"),
                        version - 1,
                        versionRow.get("user_id") == null);
        return count != null && count == 1;
    }

    private static List<Map<String, Object>> eventsOfType(
            List<Map<String, Object>> events, String subjectType) {
        return events.stream().filter(e -> subjectType.equals(e.get("subject_type"))).toList();
    }

    private static boolean hasValidEuiEvent(
            List<Map<String, Object>> euiEvents, Map<String, Object> versionRow) {
        if (euiEvents.size() != 1) return false;
        var event = euiEvents.getFirst();
        if (!Objects.equals(event.get("subject_id"), versionRow.get("eui_id"))) return false;
        return Objects.equals(event.get("subject_version"), versionRow.get("version"))
                && positioned(event, "IDENTITY");
    }

    private static boolean hasValidOperationEffects(
            Evidence evidence, Map<String, Object> versionRow, Map<String, Object> euiEvent) {
        if (!"REGISTRATION".equals(evidence.request().get("operation"))) {
            return evidence.users().isEmpty() && evidence.events().size() == 1;
        }
        if (evidence.users().size() != 1 || evidence.events().size() != 2) return false;
        var userRow = evidence.users().getFirst();
        var userEvents = eventsOfType(evidence.events(), "POCOMA_USER");
        if (userEvents.size() != 1) return false;
        var userEvent = userEvents.getFirst();
        return matchesRegistrationUser(userRow, versionRow, userEvent, euiEvent);
    }

    private static boolean matchesRegistrationUser(
            Map<String, Object> userRow,
            Map<String, Object> versionRow,
            Map<String, Object> userEvent,
            Map<String, Object> euiEvent) {
        if (!Objects.equals(userRow.get("user_id"), versionRow.get("user_id"))) return false;
        if (!Objects.equals(userRow.get("writer_xid"), versionRow.get("writer_xid"))) return false;
        if (!Objects.equals(userEvent.get("subject_id"), userRow.get("user_id"))) return false;
        if (!Objects.equals(userEvent.get("subject_version"), 1L)) return false;
        if (!positioned(userEvent, "IDENTITY")) return false;
        long euiPos = ((Number) euiEvent.get("position")).longValue();
        long userPos = ((Number) userEvent.get("position")).longValue();
        return euiPos == userPos + 1;
    }

    private static boolean positioned(Map<String, Object> row, String family) {
        return family.equals(row.get("family"))
                && row.get("position") instanceof Number n
                && n.longValue() > 0;
    }
}
