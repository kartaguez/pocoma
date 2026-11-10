package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.materialize.commandresult.v3.CommandTerminalSource;
import com.kartaguez.pocoma.domain.projection.v3.CommandTerminalResult;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcCommandTerminalSource implements CommandTerminalSource {
    private final DataSource source; private final JdbcTemplate sql; private final JdbcPotCommandStore commands;
    public JdbcCommandTerminalSource(DataSource source,SpringAttemptTransactions owner) { this.source=source; sql=new JdbcTemplate(source); commands=new JdbcPotCommandStore(source,owner); }
    public CommandTerminalResult load(UUID event,RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(source,context,() -> {
            var ids=sql.query("select e.subject_id from v3_command_completion_event e join v3_event_position p on p.event_id=e.event_id and p.family='COMMAND_COMPLETION' join v3_event_counter c on c.family=p.family and c.position>=p.position where e.event_id=? and e.subject_type='COMMAND'",(r,n) -> r.getObject(1,UUID.class),event);
            if(ids.size()!=1) throw new IllegalStateException("Missing exact Command completion");
            var stored=commands.load(ids.getFirst()); var command=stored.command(); var terminal=stored.terminal().orElseThrow(() -> new IllegalStateException("Completion without terminal"));
            return new CommandTerminalResult(command.requestId(),command.intent().getClass().getSimpleName(),new ExternalIdentity(command.requester().issuer(),command.requester().subject()),command.potId().value(),terminal.outcome().name(),terminal.resultingPotVersion(),terminal.rejection());
        });
    }
}
