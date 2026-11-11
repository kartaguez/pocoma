package com.kartaguez.pocoma.infra.postgres.source.v3.identity;
import com.kartaguez.pocoma.engine.materialize.results.v3.IdentityTerminalSource;
import com.kartaguez.pocoma.domain.projection.v3.IdentityTerminalResult;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.ExternalUserIdentityId;
import com.kartaguez.pocoma.domain.identity.v3.IdentityTransitionResult;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
/** Immutable completion projection, validated against the exact historical target. */
public final class JdbcIdentityTerminalSource implements IdentityTerminalSource {
    private final DataSource source; private final JdbcTemplate sql; private final JdbcCurrentBindingSource history;
    public JdbcIdentityTerminalSource(DataSource source) { this.source=Objects.requireNonNull(source); sql=new JdbcTemplate(source); history=new JdbcCurrentBindingSource(source); }
    public JdbcIdentityTerminalSource(DataSource source,SpringAttemptTransactions owner) { this(source); Objects.requireNonNull(owner); }
    public IdentityTerminalResult load(UUID event,RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(source,context,() -> {
            var rows=sql.queryForList("""
                select e.subject_type,c.request_id,c.operation,c.requester_issuer,c.requester_subject,
                       c.target_issuer,c.target_subject,c.status,r.outcome,r.target_eui_id,r.target_version,r.rejection
                from v3_identity_completion_event e
                join v3_event_position p on p.event_id=e.event_id and p.family='IDENTITY_COMPLETION'
                join v3_event_counter k on k.family=p.family and k.position>=p.position
                join v3_identity_request c on c.request_id=e.subject_id
                join v3_identity_request_result r on r.request_id=c.request_id
                where e.event_id=?
                """,event);
            if(rows.size()!=1) throw new IllegalStateException("Missing exact Identity completion");
            var row=rows.getFirst(); var operation=(String)row.get("operation");
            if(!"TERMINAL".equals(row.get("status")) || !operation.equals(row.get("subject_type")))
                throw new IllegalStateException("Identity operation mismatch");
            var requester=new ExternalIdentity((String)row.get("requester_issuer"),(String)row.get("requester_subject"));
            var target=new ExternalIdentity((String)row.get("target_issuer"),(String)row.get("target_subject"));
            boolean changed="CHANGED".equals(row.get("outcome"));
            var version=(Number)row.get("target_version");
            var rejection=(String)row.get("rejection");
            if(rejection!=null) IdentityTransitionResult.Rejection.valueOf(rejection);
            if(changed && (version==null || row.get("target_eui_id")==null || !history.at(new ExternalUserIdentityId((UUID)row.get("target_eui_id")),version.longValue(),context).identity().equals(target)))
                throw new IllegalStateException("Identity result target mismatch");
            if(!changed && row.get("target_eui_id")!=null) throw new IllegalStateException("Rejected Identity has target version");
            return new IdentityTerminalResult((UUID)row.get("request_id"),operation,requester,(String)row.get("outcome"),changed?target:null,version==null?null:version.longValue(),rejection);
        });
    }
}
