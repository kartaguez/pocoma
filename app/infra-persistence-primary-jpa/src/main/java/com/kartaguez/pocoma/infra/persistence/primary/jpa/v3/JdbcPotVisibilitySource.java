package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;
import static com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotHistoryRows.*;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.v3.PotVisibility;
import com.kartaguez.pocoma.engine.consume.projectiontask.v3.PotVisibilitySource;
import com.kartaguez.pocoma.engine.consume.projectiontask.v3.PotVisibilityRules;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import javax.sql.DataSource;
import java.util.*;
public final class JdbcPotVisibilitySource implements PotVisibilitySource {
    private final PotHistoryRows rows;
    public JdbcPotVisibilitySource(DataSource source) { rows=new PotHistoryRows(source); }
    public PotVisibility load(UUID event,RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(rows.dataSource,context,() -> {
            var found=rows.jdbc.queryForList("select e.pot_id,e.subject_version from v3_pot_event e join v3_event_position p on p.event_id=e.event_id and p.family='POT' join v3_event_counter c on c.family=p.family and c.position>=p.position where e.event_id=? and e.subject_type='POT'",event);
            if(found.size()!=1) throw bad("Missing exact Visibility event");
            var r=found.getFirst(); return at(uuid(r,"pot_id"),number(r,"subject_version"),context);
        });
    }
    public PotVisibility at(UUID id,long v,RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(rows.dataSource,context,() -> {
            rows.requireTransaction(true); var pot=new PotId(id); var meta=rows.version(pot,v); if(meta==null) throw bad("Missing historical Visibility source");
            long latest=number(meta,"latest"); var birth=Map.of(id,1L);
            var header=rows.component("pot_header",pot,v,latest,birth).get(id);
            if((boolean)header.get("deleted")) return new PotVisibility(id,v,Set.of());
            var creator=uuid(rows.component("pot_details",pot,v,latest,birth,"creator_id").get(id),"creator_id");
            var sb=rows.identities("shareholder",pot,null,v); var sh=rows.component("shareholder_header",pot,v,latest,sb); var sd=rows.component("shareholder_details",pot,v,latest,sb,"user_id");
            var attachments=sb.keySet().stream().map(s -> new PotVisibilityRules.Attachment((boolean)sh.get(s).get("deleted"),uuid(sd.get(s),"user_id"))).toList();
            return new PotVisibility(id,v,PotVisibilityRules.visible(creator,false,attachments));
        });
    }
}
