package com.kartaguez.pocoma.infra.persistence.jpa.adapter.registration;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.registration.ImmutableRegistrationResult;
import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;
import com.kartaguez.pocoma.engine.registration.RegistrationResultStore;

@Component
public final class JdbcRegistrationResultStore implements RegistrationResultStore {
    private final JdbcTemplate jdbc;
    public JdbcRegistrationResultStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void ensureResult(ImmutableRegistrationResult result) {
        RegistrationOutcome outcome = result.outcome();
        boolean registered = outcome instanceof RegistrationOutcome.Registered;
        UUID user = registered ? ((RegistrationOutcome.Registered) outcome).userId().value() : null;
        UUID binding = registered ? ((RegistrationOutcome.Registered) outcome).bindingId().value() : null;
        jdbc.update("""
                insert into registration_results(request_id,owner_issuer,owner_subject,schema_version,
                    outcome_type,user_id,binding_id,rejection_code)
                values (?,?,?,1,?,?,?,?) on conflict (request_id) do nothing
                """, outcome.requestId(), result.owner().issuer(), result.owner().subject(),
                registered ? "REGISTERED" : "REJECTED", user, binding,
                registered ? null : RegistrationOutcome.Rejected.CODE);
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
            RegistrationOutcome outcome = switch (rs.getString("outcome_type")) {
                case "REGISTERED" -> new RegistrationOutcome.Registered(requestId,
                        new PocomaUserId(rs.getObject("user_id", UUID.class)),
                        new BindingId(rs.getObject("binding_id", UUID.class)));
                case "REJECTED" -> {
                    if (!RegistrationOutcome.Rejected.CODE.equals(rs.getString("rejection_code")))
                        throw new IllegalStateException("Invalid Registration Result rejection code");
                    yield new RegistrationOutcome.Rejected(requestId);
                }
                default -> throw new IllegalStateException("Invalid Registration Result shape");
            };
            return new ImmutableRegistrationResult(
                    new ExternalIdentity(rs.getString("owner_issuer"), rs.getString("owner_subject")), outcome);
        }, requestId).stream().findFirst();
    }
}
