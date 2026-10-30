package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestObservation;
import com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

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

    @Override
    public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
        SpringAttemptTransactions.requireRead(source, context);
        var requests =
                jdbc.queryForList("select * from v3_identity_request where request_id=?", id);
        if (requests.size() != 1) return RecoveryObservation.INCONSISTENT;
        var c = requests.getFirst();
        // Decode provenance in this same snapshot; corrupted durable input is not rollback
        // evidence.
        try {
            var admission = IdentityRequestJson.decode(c.get("admission_context").toString());
            if (!admission.initiator().issuer().equals(c.get("requester_issuer"))
                    || !admission.initiator().subject().equals(c.get("requester_subject"))
                    || !admission.target().issuer().equals(c.get("target_issuer"))
                    || !admission.target().subject().equals(c.get("target_subject")))
                return RecoveryObservation.INCONSISTENT;
        } catch (RuntimeException invalid) {
            return RecoveryObservation.INCONSISTENT;
        }
        var results =
                jdbc.queryForList(
                        "select * from v3_identity_request_result where request_id=?", id);
        var users =
                jdbc.queryForList("select * from v3_pocoma_user where created_request_id=?", id);
        var states =
                jdbc.queryForList(
                        """
select v.*,a.issuer,a.subject from v3_eui_version v join v3_eui_authority a using(eui_id) where v.request_id=?
""",
                        id);
        var events =
                jdbc.queryForList(
                        """
select e.*,p.position,p.family from v3_identity_event e left join v3_event_position p using(event_id) where e.request_id=?
""",
                        id);
        var completions =
                jdbc.queryForList(
                        """
select e.*,p.position,p.family from v3_identity_completion_event e left join v3_event_position p using(event_id) where e.subject_id=?
""",
                        id);
        if ("PENDING".equals(c.get("status")))
            return results.isEmpty()
                            && users.isEmpty()
                            && states.isEmpty()
                            && events.isEmpty()
                            && completions.isEmpty()
                    ? RecoveryObservation.NOT_COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        if (!"TERMINAL".equals(c.get("status")) || results.size() != 1 || completions.size() != 1)
            return RecoveryObservation.INCONSISTENT;
        var completion = completions.getFirst();
        if (!Objects.equals(c.get("operation"), completion.get("subject_type"))
                || !positioned(completion, "IDENTITY_COMPLETION"))
            return RecoveryObservation.INCONSISTENT;
        var r = results.getFirst();
        if ("REJECTED".equals(r.get("outcome")))
            return r.get("rejection") != null
                            && r.get("target_eui_id") == null
                            && r.get("target_version") == null
                            && users.isEmpty()
                            && states.isEmpty()
                            && events.isEmpty()
                    ? RecoveryObservation.COMPLETED
                    : RecoveryObservation.INCONSISTENT;
        if (!"CHANGED".equals(r.get("outcome")) || r.get("rejection") != null || states.size() != 1)
            return RecoveryObservation.INCONSISTENT;
        var v = states.getFirst();
        if (!Objects.equals(r.get("target_eui_id"), v.get("eui_id"))
                || !Objects.equals(r.get("target_version"), v.get("version"))
                || !Objects.equals(c.get("target_issuer"), v.get("issuer"))
                || !Objects.equals(c.get("target_subject"), v.get("subject"))
                || "UNLINK".equals(c.get("operation")) != (v.get("user_id") == null)
                || (v.get("user_id") == null) != (v.get("binding_token") == null))
            return RecoveryObservation.INCONSISTENT;
        long version = ((Number) v.get("version")).longValue();
        if (version < 1
                || version > 1
                        && jdbc.queryForObject(
                                        "select count(*) from v3_eui_version where eui_id=? and"
                                                + " version=? and (user_id is null)<>?",
                                        Long.class,
                                        v.get("eui_id"),
                                        version - 1,
                                        v.get("user_id") == null)
                                != 1) return RecoveryObservation.INCONSISTENT;
        var eui =
                events.stream()
                        .filter(e -> "EXTERNAL_USER_IDENTITY".equals(e.get("subject_type")))
                        .toList();
        if (eui.size() != 1
                || !Objects.equals(eui.getFirst().get("subject_id"), v.get("eui_id"))
                || !Objects.equals(eui.getFirst().get("subject_version"), v.get("version"))
                || !positioned(eui.getFirst(), "IDENTITY")) return RecoveryObservation.INCONSISTENT;
        if ("REGISTRATION".equals(c.get("operation"))) {
            if (users.size() != 1 || events.size() != 2) return RecoveryObservation.INCONSISTENT;
            var u = users.getFirst();
            var ue =
                    events.stream()
                            .filter(e -> "POCOMA_USER".equals(e.get("subject_type")))
                            .toList();
            if (!Objects.equals(u.get("user_id"), v.get("user_id"))
                    || !Objects.equals(u.get("writer_xid"), v.get("writer_xid"))
                    || ue.size() != 1
                    || !Objects.equals(ue.getFirst().get("subject_id"), u.get("user_id"))
                    || !Objects.equals(ue.getFirst().get("subject_version"), 1L)
                    || !positioned(ue.getFirst(), "IDENTITY")
                    || ((Number) eui.getFirst().get("position")).longValue()
                            != ((Number) ue.getFirst().get("position")).longValue() + 1)
                return RecoveryObservation.INCONSISTENT;
        } else if (!users.isEmpty() || events.size() != 1) return RecoveryObservation.INCONSISTENT;
        return RecoveryObservation.COMPLETED;
    }

    private static boolean positioned(Map<String, Object> row, String family) {
        return family.equals(row.get("family"))
                && row.get("position") instanceof Number n
                && n.longValue() > 0;
    }
}
