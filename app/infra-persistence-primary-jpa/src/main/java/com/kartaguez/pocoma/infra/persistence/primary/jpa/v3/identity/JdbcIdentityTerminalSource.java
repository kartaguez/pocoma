package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;
import com.kartaguez.pocoma.engine.materialize.registrationresult.v3.IdentityTerminalSource;
import com.kartaguez.pocoma.domain.projection.v3.IdentityTerminalResult;
import com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcIdentityTerminalSource implements IdentityTerminalSource {
    private final DataSource source; private final JdbcTemplate sql; private final JdbcIdentityRequestStore requests; private final JdbcIdentityAuthority authority;
    public JdbcIdentityTerminalSource(DataSource source,SpringAttemptTransactions owner) { this.source=source; sql=new JdbcTemplate(source); requests=new JdbcIdentityRequestStore(source,owner); authority=new JdbcIdentityAuthority(source,owner); }
    public IdentityTerminalResult load(UUID event,RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(source,context,() -> {
            var rows=sql.queryForList("select e.subject_id,e.subject_type from v3_identity_completion_event e join v3_event_position p on p.event_id=e.event_id and p.family='IDENTITY_COMPLETION' join v3_event_counter c on c.family=p.family and c.position>=p.position where e.event_id=?",event);
            if(rows.size()!=1) throw new IllegalStateException("Missing exact Identity completion");
            var row=rows.getFirst(); var stored=requests.load((UUID)row.get("subject_id")).orElseThrow(); var request=stored.request();
            var terminal=stored.terminal().orElseThrow(() -> new IllegalStateException("Completion without terminal"));
            String operation=request.operation().getClass().getSimpleName().toUpperCase(Locale.ROOT);
            if(!operation.equals(row.get("subject_type"))) throw new IllegalStateException("Identity operation mismatch");
            boolean changed=terminal.outcome()==IdentityRequestStore.Outcome.CHANGED;
            if(changed && !authority.at(terminal.targetId(),terminal.resultingVersion()).orElseThrow().identity().equals(request.target())) throw new IllegalStateException("Identity result target mismatch");
            return new IdentityTerminalResult(request.requestId(),operation,request.requesterEui(),terminal.outcome().name(),changed?request.target():null,terminal.resultingVersion(),terminal.rejection()==null?null:terminal.rejection().name());
        });
    }
}
