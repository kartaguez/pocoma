package com.kartaguez.pocoma.infra.postgres.source;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.kartaguez.pocoma.domain.pot.PotFinancialState;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceSource;
import com.kartaguez.pocoma.infra.postgres.core.history.PostgresPotHistoryReader;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
public final class JdbcPotBalanceSource implements PotBalanceSource {
    private final DataSource source; private final JdbcTemplate sql; private final PostgresPotHistoryReader history;
    public JdbcPotBalanceSource(DataSource source) { this.source=source; sql=new JdbcTemplate(source); history=new PostgresPotHistoryReader(source); }
    public PotFinancialState load(UUID event,RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(source,context,() -> {
            var rows=sql.queryForList("select e.pot_id,e.subject_version from v3_pot_event e join v3_event_position p on p.event_id=e.event_id and p.family='POT' join v3_event_counter c on c.family=p.family and c.position>=p.position where e.event_id=? and e.subject_type='POT'",event);
            if(rows.size()!=1) throw new IllegalStateException("Missing exact Balance event");
            var r=rows.getFirst(); return history.financialAtVersion(new PotId((UUID)r.get("pot_id")),((Number)r.get("subject_version")).longValue()).orElseThrow(() -> new IllegalStateException("Missing historical finance"));
        });
    }
}
