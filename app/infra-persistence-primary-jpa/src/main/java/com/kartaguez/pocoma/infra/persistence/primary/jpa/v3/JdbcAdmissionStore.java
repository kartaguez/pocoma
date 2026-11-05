package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.contracts.command.v3.*;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.engine.admit.command.v3.AdmitCommandV3;
import com.kartaguez.pocoma.engine.admit.registration.v3.AdmitIdentityRequestV3;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.IdentityRequestJson;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.*;

import javax.sql.DataSource;

/** Dedicated admission authority. Every write requires the identical physical attempt owner. */
public final class JdbcAdmissionStore
        implements AdmitCommandV3.Store, AdmitIdentityRequestV3.Store {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcAdmissionStore(DataSource source, SpringAttemptTransactions owner) {
        this.source = Objects.requireNonNull(source);
        owner.requireSource(source);
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public Optional<AdmissionAssociation> find(byte[] scope) {
        return SpringAttemptTransactions.participating(
                source,
                () -> {
                    var rows =
                            jdbc.query(
                                    "select"
                                        + " request_id,category,operation,canonical_version,lookup_key_id,comparison_key_id,scope_key_fingerprint,intent_fingerprint,created_at"
                                        + " from v3_admission_association where"
                                        + " scope_key_fingerprint=?",
                                    (r, n) ->
                                            new AdmissionAssociation(
                                                    r.getObject(1, UUID.class),
                                                    r.getString(2),
                                                    r.getString(3),
                                                    r.getInt(4),
                                                    r.getString(5),
                                                    r.getString(6),
                                                    r.getBytes(7),
                                                    r.getBytes(8),
                                                    r.getTimestamp(9).toInstant()),
                                    scope);
                    if (rows.size() > 1) throw new IllegalStateException("Nonunique admission");
                    return rows.stream().findFirst();
                });
    }

    @Override
    public boolean reserve(AdmissionAssociation a) {
        return SpringAttemptTransactions.guarded(
                source,
                () ->
                        !jdbc.queryForList(
                                        """
insert into v3_admission_association(request_id,category,operation,canonical_version,lookup_key_id,comparison_key_id,
  scope_key_fingerprint,intent_fingerprint,created_at,command_id,identity_request_id)
values (?,?,?,?,?,?,?,?,?,?,?) on conflict (scope_key_fingerprint) do nothing returning request_id
""",
                                        UUID.class,
                                        a.requestId(),
                                        a.category(),
                                        a.operation(),
                                        a.canonicalVersion(),
                                        a.lookupKeyId(),
                                        a.comparisonKeyId(),
                                        a.scopeFingerprint(),
                                        a.intentFingerprint(),
                                        Timestamp.from(a.createdAt()),
                                        a.category().equals("COMMAND") ? a.requestId() : null,
                                        a.category().equals("IDENTITY") ? a.requestId() : null)
                                .isEmpty());
    }

    @Override
    public void insert(PotCommand c) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    jdbc.update(
                            "insert into"
                                + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                + " values (?,?,?,?,?,?,?,?::jsonb)",
                            c.requestId(),
                            c.requester().issuer(),
                            c.requester().subject(),
                            c.bindingToken(),
                            c.potId().value(),
                            c.observedVersion(),
                            c.intent().getClass().getSimpleName(),
                            PotCommandJson.encode(c));
                    return null;
                });
    }

    @Override
    public void insert(IdentityRequest r) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    if (r.admission().origin() != IdentityRequest.Origin.AUTHENTICATED_HTTP
                            || r.operation() instanceof IdentityRequest.Link)
                        throw new IllegalArgumentException("Public Identity operation refused");
                    jdbc.update(
                            "insert into"
                                + " v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at)"
                                + " values (?,?,?,?,?,?,?,?::jsonb,?)",
                            r.requestId(),
                            r.requesterEui().issuer(),
                            r.requesterEui().subject(),
                            IdentityRequestJson.operation(r),
                            r.bindingToken() == null ? null : r.bindingToken().value(),
                            r.target().issuer(),
                            r.target().subject(),
                            IdentityRequestJson.encode(r.admission()),
                            Timestamp.from(r.createdAt()));
                    return null;
                });
    }
}
