package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.read.registrationresult.ImmutableRegistrationResult;
import com.kartaguez.pocoma.engine.read.registrationresult.PublishedRegistrationResult;
import com.kartaguez.pocoma.engine.read.registrationresult.RegistrationResultStore;

@Component
public final class JdbcRegistrationResultStore implements RegistrationResultStore {
    private final JdbcTemplate jdbc;
    public JdbcRegistrationResultStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void ensureResult(ImmutableRegistrationResult result) {
        PublishedRegistrationResult outcome = result.outcome();
        boolean registered = outcome instanceof PublishedRegistrationResult.Registered;
        UUID user = registered ? ((PublishedRegistrationResult.Registered) outcome).userId().value() : null;
        UUID binding = registered ? ((PublishedRegistrationResult.Registered) outcome).bindingId().value() : null;
        jdbc.update("""
                insert into registration_results(request_id,owner_issuer,owner_subject,schema_version,
                    outcome_type,user_id,binding_id,rejection_code)
                values (?,?,?,1,?,?,?,?) on conflict (request_id) do nothing
                """, outcome.requestId(), result.owner().issuer(), result.owner().subject(),
                registered ? "REGISTERED" : "REJECTED", user, binding,
                registered ? null : PublishedRegistrationResult.Rejected.CODE);
        if (!find(outcome.requestId()).orElseThrow().equals(result)) {
            throw new IllegalStateException("Divergent immutable Registration Result for " + outcome.requestId());
        }
    }

    @Override public Optional<ImmutableRegistrationResult> find(UUID requestId) {
        return jdbc.query("""
                select owner_issuer,owner_subject,schema_version,outcome_type,user_id,binding_id,rejection_code
                from registration_results where request_id=?
                """, (rs, n) -> {
            if (rs.getInt("schema_version") != 1) throw new IllegalStateException("Unsupported Registration Result schema");
            PublishedRegistrationResult outcome = switch (rs.getString("outcome_type")) {
                case "REGISTERED" -> new PublishedRegistrationResult.Registered(requestId,
                        new PocomaUserId(rs.getObject("user_id", UUID.class)),
                        new BindingId(rs.getObject("binding_id", UUID.class)));
                case "REJECTED" -> {
                    if (!PublishedRegistrationResult.Rejected.CODE.equals(rs.getString("rejection_code")))
                        throw new IllegalStateException("Invalid Registration Result rejection code");
                    yield new PublishedRegistrationResult.Rejected(requestId);
                }
                default -> throw new IllegalStateException("Invalid Registration Result shape");
            };
            return new ImmutableRegistrationResult(
                    new ExternalIdentity(rs.getString("owner_issuer"), rs.getString("owner_subject")), outcome);
        }, requestId).stream().findFirst();
    }
}
