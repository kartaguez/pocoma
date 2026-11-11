package com.kartaguez.pocoma.infra.postgres.source.v3.identity;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.PocomaUserId;
import com.kartaguez.pocoma.domain.identity.v3.*;
import com.kartaguez.pocoma.engine.materialize.identity.v3.CurrentBindingSource;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
/** Historical identity reader with no WRITE authority implementation. */
public final class JdbcCurrentBindingSource implements CurrentBindingSource {
    private final DataSource source; private final JdbcTemplate sql;
    public JdbcCurrentBindingSource(DataSource source) { this.source=Objects.requireNonNull(source); sql=new JdbcTemplate(source); }
    public JdbcCurrentBindingSource(DataSource source,SpringAttemptTransactions owner) { this(source); Objects.requireNonNull(owner); }
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
        return SpringAttemptTransactions.observed(source,context,() -> {
            var rows=sql.query("""
                select a.eui_id,a.issuer,a.subject,v.version,v.user_id,v.binding_token
                from v3_eui_version v join v3_eui_authority a using(eui_id)
                where v.eui_id=? and v.version=?
                """,(rs,n) -> {
                    var user=rs.getObject("user_id",UUID.class);
                    return new ExternalUserIdentity(new ExternalUserIdentityId(rs.getObject("eui_id",UUID.class)),
                        new ExternalIdentity(rs.getString("issuer"),rs.getString("subject")),rs.getLong("version"),
                        user==null?null:new PocomaUserId(user),user==null?null:new BindingToken(rs.getString("binding_token")));
                }, id.value(), version);
            if(rows.size()!=1) throw new IllegalStateException("Missing exact EUI version");
            return rows.getFirst();
        });
    }
}
