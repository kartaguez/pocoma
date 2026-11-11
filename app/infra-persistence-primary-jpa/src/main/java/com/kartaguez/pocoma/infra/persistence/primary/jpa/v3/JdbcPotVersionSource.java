package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotHistoryRows.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.SubobjectContent.*;
import com.kartaguez.pocoma.engine.materialize.pot.v3.PotVersionSource;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import org.springframework.jdbc.support.SqlArrayValue;
import javax.sql.DataSource;
import java.util.*;

/** Lightweight composition and separately selected immutable contents; never reads READ. */
public final class JdbcPotVersionSource implements PotVersionSource {
    private final PotHistoryRows rows;
    public JdbcPotVersionSource(DataSource source) { rows=new PotHistoryRows(source); }
    public PotVersion composition(UUID event, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(rows.dataSource,context,() -> {
            rows.requireTransaction(true);
            var eventRows=rows.jdbc.queryForList("select e.pot_id,e.subject_version from v3_pot_event e join v3_event_position p on p.event_id=e.event_id and p.family='POT' join v3_event_counter c on c.family=p.family and c.position>=p.position where e.event_id=? and e.subject_type='POT'",event);
            if (eventRows.size()!=1) throw bad("Missing exact published Pot event");
            var e=eventRows.getFirst(); var pot=new PotId(uuid(e,"pot_id")); long v=number(e,"subject_version");
            var metadata=rows.version(pot,v); if(metadata==null) throw bad("Missing event version");
            long latest=number(metadata,"latest");
            var h=rows.component("pot_header",pot,v,latest,Map.of(pot.value(),1L)).get(pot.value());
            var d=rows.component("pot_details",pot,v,latest,Map.of(pot.value(),1L),"creator_id").get(pot.value());
            var sb=rows.identities("shareholder",pot,null,v);
            var sh=rows.component("shareholder_header",pot,v,latest,sb);
            var sd=rows.component("shareholder_details",pot,v,latest,sb,"user_id");
            var sw=rows.component("shareholder_weight",pot,v,latest,sb,"local_version");
            var ss=new ArrayList<PotVersion.Shareholder>(); var attached=new HashSet<UUID>();
            for(var id:sb.keySet()) {
                boolean deleted=(boolean)sh.get(id).get("deleted"); UUID user=uuid(sd.get(id),"user_id");
                ss.add(new PotVersion.Shareholder(id,number(sh.get(id),"local_version"),number(sd.get(id),"local_version"),number(sw.get(id),"local_version"),deleted,user==null));
                if(!deleted && user!=null) attached.add(user);
            }
            var eb=rows.identities("expense",pot,null,v);
            var eh=rows.component("expense_header",pot,v,latest,eb);
            var ed=rows.component("expense_details",pot,v,latest,eb,"local_version");
            var ef=rows.component("expense_financials",pot,v,latest,eb,"local_version");
            var es=new ArrayList<PotVersion.Expense>();
            for(var id:eb.keySet()) es.add(new PotVersion.Expense(id,number(eh.get(id),"local_version"),number(ed.get(id),"local_version"),number(ef.get(id),"local_version"),(boolean)eh.get(id).get("deleted")));
            var auth=new HashSet<>(attached); auth.add(uuid(d,"creator_id"));
            return new PotVersion(pot.value(),v,number(h,"local_version"),number(d,"local_version"),(boolean)h.get("deleted"),ss,es,auth,attached);
        });
    }
    public List<SubobjectVersion> contents(PotVersion composition, Collection<SubobjectKey> requested, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(rows.dataSource,context,() -> {
            rows.requireTransaction(true);
            var unique=new TreeSet<SubobjectKey>(requested);
            if(unique.size()!=requested.size() || !new HashSet<>(composition.keys()).containsAll(unique)) throw bad("Invalid requested component keys");
            var result=new ArrayList<SubobjectVersion>();
            for(var kind:SubobjectKind.values()) {
                var keys=unique.stream().filter(k -> k.kind()==kind).toList(); if(keys.isEmpty()) continue;
                var args=new ArrayList<Object>(); args.add(composition.potId()); args.add(composition.version()); args.add(composition.version());
                var tuples=new ArrayList<String>(); for(var k:keys) { tuples.add("(?,?)"); args.add(k.objectId()); args.add(k.localVersion()); }
                String table=switch(kind) {
                    case POT_HEADER -> "pot_header"; case POT_DETAILS -> "pot_details";
                    case SHAREHOLDER_HEADER -> "shareholder_header"; case SHAREHOLDER_DETAILS -> "shareholder_details"; case SHAREHOLDER_WEIGHT -> "shareholder_weight";
                    case EXPENSE_HEADER -> "expense_header"; case EXPENSE_DETAILS -> "expense_details"; case EXPENSE_FINANCIALS -> "expense_financials";
                };
                var data=rows.jdbc.queryForList("select * from v3_"+table+" where pot_id=? and active_from<=? and (active_to is null or ?<active_to) and (object_id,local_version) in ("+String.join(",",tuples)+")",args.toArray());
                if(data.size()!=keys.size()) throw bad("Missing exact component contents");
                var participants=new HashMap<Long,Map<ShareholderId,Optional<Weight>>>();
                if(kind==SubobjectKind.EXPENSE_FINANCIALS) {
                    var revisions=data.stream().map(r -> number(r,"revision_id")).toArray();
                    for(var p:rows.named.queryForList("select * from v3_expense_participant where financial_revision_id=any(:ids)",Map.of("ids",new SqlArrayValue("bigint",revisions)))) {
                        if(!composition.potId().equals(uuid(p,"pot_id"))) throw bad("Foreign financial participant");
                        var map=participants.computeIfAbsent(number(p,"financial_revision_id"),x -> new HashMap<>());
                        Optional<Weight> w=p.get("numerator")==null && p.get("denominator")==null ? Optional.empty() : Optional.of(new Weight(JdbcPotHistoryAdapter.rational(p)));
                        if(map.put(new ShareholderId(uuid(p,"shareholder_id")),w)!=null) throw bad("Duplicate participant");
                    }
                }
                var pot=new PotId(composition.potId());
                for(var r:data) {
                    var id=uuid(r,"object_id"); var key=new SubobjectKey(pot.value(),kind,id,number(r,"local_version"));
                    SubobjectContent content=switch(kind) {
                        case POT_HEADER -> new PotHeaderContent(new PotHeader(pot,(boolean)r.get("deleted")));
                        case POT_DETAILS -> new PotDetailsContent(new PotDetails(pot,new Label((String)r.get("label")),new UserId(uuid(r,"creator_id"))));
                        case SHAREHOLDER_HEADER -> new ShareholderHeaderContent(new ShareholderHeader(pot,new ShareholderId(id),(boolean)r.get("deleted")));
                        case SHAREHOLDER_DETAILS -> new ShareholderDetailsContent(new ShareholderDetails(pot,new ShareholderId(id),new Name((String)r.get("name")),Optional.ofNullable(uuid(r,"user_id")).map(UserId::new)));
                        case SHAREHOLDER_WEIGHT -> new ShareholderWeightContent(new ShareholderWeight(pot,new ShareholderId(id),new Weight(JdbcPotHistoryAdapter.rational(r))));
                        case EXPENSE_HEADER -> new ExpenseHeaderContent(new ExpenseHeader(pot,new ExpenseId(id),(boolean)r.get("deleted")));
                        case EXPENSE_DETAILS -> new ExpenseDetailsContent(new ExpenseDetails(pot,new ExpenseId(id),new Label((String)r.get("label")),((java.sql.Date)r.get("expense_date")).toLocalDate()));
                        case EXPENSE_FINANCIALS -> {
                            var shares=participants.get(number(r,"revision_id")); if(shares==null || shares.isEmpty()) throw bad("Missing financial participants");
                            var payer=new ShareholderId(uuid(r,"payer_id")); var refs=new HashSet<>(shares.keySet()); refs.add(payer);
                            boolean deleted=composition.expenses().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().deleted();
                            for(var ref:refs) if(composition.shareholders().stream().noneMatch(s -> s.id().equals(ref.value()) && (deleted || !s.deleted()))) throw bad("Invalid financial reference");
                            yield new ExpenseFinancialsContent(new ExpenseFinancials(pot,new ExpenseId(id),new Amount(JdbcPotHistoryAdapter.rational(r)),payer,shares));
                        }
                    };
                    result.add(new SubobjectVersion(key,content));
                }
            }
            result.sort(Comparator.comparing(SubobjectVersion::key)); return List.copyOf(result);
        });
    }
}
