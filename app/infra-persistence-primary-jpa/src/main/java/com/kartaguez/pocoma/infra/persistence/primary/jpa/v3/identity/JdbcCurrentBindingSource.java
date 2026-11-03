package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.materialize.currentbinding.v3.CurrentBindingSource;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcCurrentBindingSource implements CurrentBindingSource {
    private final DataSource source; private final JdbcTemplate sql; private final JdbcIdentityAuthority authority;
    public JdbcCurrentBindingSource(DataSource source,SpringAttemptTransactions owner) { this.source=source; sql=new JdbcTemplate(source); authority=new JdbcIdentityAuthority(source,owner); }
    public Optional<ExternalUserIdentity> load(UUID event,RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(source,context,() -> {
            var rows=sql.queryForList("select e.subject_type,e.subject_id,e.subject_version from v3_identity_event e join v3_event_position p on p.event_id=e.event_id and p.family='IDENTITY' join v3_event_counter c on c.family=p.family and c.position>=p.position where e.event_id=?",event);
            if(rows.size()!=1) throw new IllegalStateException("Missing exact Identity event");
            var r=rows.getFirst(); if("POCOMA_USER".equals(r.get("subject_type"))) return Optional.empty();
            if(!"EXTERNAL_USER_IDENTITY".equals(r.get("subject_type"))) throw new IllegalStateException("Wrong Identity subject");
            return Optional.of(at(new ExternalUserIdentityId((UUID)r.get("subject_id")),((Number)r.get("subject_version")).longValue(),context));
        });
    }
    public ExternalUserIdentity at(ExternalUserIdentityId id,long version,RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(source,context,() -> authority.at(id,version).orElseThrow(() -> new IllegalStateException("Missing exact EUI version")));
    }
}
