package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.contracts.command.v3.*;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.contracts.registration.v3.LinkProposal;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;
import com.kartaguez.pocoma.engine.admit.registration.v3.AdmitLinkProposal;
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
        implements AdmitCommandV3.Store, AdmitIdentityRequestV3.Store, AdmitLinkProposal.Store {
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
                    publishPosition("COMMAND", c.requestId());
                    return null;
                });
    }

    @Override
    public void insert(IdentityRequest r) {
        if (r.operation() instanceof IdentityRequest.Link)
            throw new IllegalArgumentException("Public Identity operation refused");
        insertHttpIdentity(r);
    }

    @Override
    public void insertAcceptedLink(IdentityRequest r) {
        if (!(r.operation() instanceof IdentityRequest.Link)
                || !r.admission().validator().equals("pocoma-v3-link-post"))
            throw new IllegalArgumentException("Accepted Link required");
        insertHttpIdentity(r); // Deferred SQL guard requires the exact accepted proposal at commit.
    }

    private void insertHttpIdentity(IdentityRequest r) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    if (r.admission().origin() != IdentityRequest.Origin.AUTHENTICATED_HTTP)
                        throw new IllegalArgumentException("Public Identity operation refused");
                    jdbc.update(
                            "insert into"
                                + " v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at,link_proposal_id)"
                                + " values (?,?,?,?,?,?,?,?::jsonb,?,?)",
                            r.requestId(),
                            r.requesterEui().issuer(),
                            r.requesterEui().subject(),
                            IdentityRequestJson.operation(r),
                            r.bindingToken() == null ? null : r.bindingToken().value(),
                            r.target().issuer(),
                            r.target().subject(),
                            IdentityRequestJson.encode(r.admission()),
                            Timestamp.from(r.createdAt()),
                            r.operation() instanceof IdentityRequest.Link ? r.requestId() : null);
                    publishPosition("IDENTITY_REQUEST", r.requestId());
                    return null;
                });
    }

    private void publishPosition(String family, UUID requestId) {
        Long pos =
                jdbc.queryForObject(
                        "update v3_request_counter set position=position+1 where family=? returning"
                                + " position",
                        Long.class,
                        family);
        if (pos == null) throw new IllegalStateException("Missing request counter: " + family);
        jdbc.update(
                "insert into v3_request_position(family,position,request_id) values (?,?,?)",
                family,
                pos,
                requestId);
    }

    @Override public java.time.Instant now() {
        return SpringAttemptTransactions.participating(source,
                () -> jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant());
    }

    private static final String PROPOSAL_COLUMNS = "link_request_id,initiator_issuer,initiator_subject,"
            + "target_issuer,target_subject,binding_token,authenticated_at,issued_at,principal_expires_at,"
            + "created_at,expires_at,canonical_version,lookup_key_id,comparison_key_id,scope_key_fingerprint,"
            + "intent_fingerprint,accepted_at,identity_request_id";

    private Optional<LinkProposal> proposals(String predicate, Object key) {
        return SpringAttemptTransactions.participating(source, () -> jdbc.query(
                "select " + PROPOSAL_COLUMNS + " from v3_link_proposal where " + predicate,
                (r, n) -> new LinkProposal(r.getObject(1, UUID.class),
                        new ExternalIdentity(r.getString(2), r.getString(3)),
                        new ExternalIdentity(r.getString(4), r.getString(5)), new BindingToken(r.getString(6)),
                        r.getTimestamp(7).toInstant(), r.getTimestamp(8).toInstant(), r.getTimestamp(9).toInstant(),
                        r.getTimestamp(10).toInstant(), r.getTimestamp(11).toInstant(), r.getInt(12),
                        r.getString(13), r.getString(14), r.getBytes(15), r.getBytes(16),
                        r.getTimestamp(17) == null ? null : r.getTimestamp(17).toInstant(), r.getObject(18, UUID.class)),
                key).stream().findFirst());
    }
    @Override public Optional<LinkProposal> proposalByScope(byte[] scope) {
        return proposals("scope_key_fingerprint=?", scope);
    }
    @Override public Optional<LinkProposal> proposal(UUID id, boolean lock) {
        if (lock) return SpringAttemptTransactions.guarded(source,
                () -> proposals("link_request_id=? for update", id));
        return proposals("link_request_id=?", id);
    }
    @Override public boolean insertProposal(LinkProposal p) {
        return SpringAttemptTransactions.guarded(source, () -> !jdbc.queryForList(
                "insert into v3_link_proposal(" + PROPOSAL_COLUMNS + ") values "
                        + "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(scope_key_fingerprint) do nothing returning link_request_id",
                UUID.class, p.linkRequestId(), p.initiator().issuer(), p.initiator().subject(),
                p.target().issuer(), p.target().subject(), p.bindingToken().value(),
                Timestamp.from(p.authenticatedAt()), Timestamp.from(p.issuedAt()), Timestamp.from(p.principalExpiresAt()),
                Timestamp.from(p.createdAt()), Timestamp.from(p.expiresAt()), p.canonicalVersion(), p.lookupKeyId(),
                p.comparisonKeyId(), p.scopeFingerprint(), p.intentFingerprint(), null, null).isEmpty());
    }
    @Override public void accept(UUID id, java.time.Instant now) {
        SpringAttemptTransactions.guarded(source, () -> {
            if (jdbc.update("update v3_link_proposal set accepted_at=?,identity_request_id=link_request_id "
                    + "where link_request_id=? and identity_request_id is null", Timestamp.from(now), id) != 1)
                throw new IllegalStateException("Link acceptance lost");
            return null;
        });
    }
}
