package com.kartaguez.pocoma.infra.postgres.write.v3;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
@Testcontainers
class PotVersionSourcePostgresTest {
    @Container static final PostgreSQLContainer PG=CommandExecutionPostgresTest.POSTGRES;
    @Test void exactHistoricalComposition() throws Exception {
        var f=new CommandExecutionPostgresTest(); f.setup();
        try {
            f.base(); var source=new JdbcPotVersionSource(f.source);
            UUID e1=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=1",UUID.class);
            UUID e2=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=2",UUID.class);
            var v2=f.tx.read(c -> source.composition(e2,c)); var v1=f.tx.read(c -> source.composition(e1,c));
            assertEquals(3,v2.shareholders().size(),"P01-A/B complete references"); assertEquals(0,v1.shareholders().size());
            assertEquals(Set.of(f.creator.value(),f.member.value()),v2.authorizedUserIds()); assertEquals(Set.of(f.member.value()),v2.attachedActiveUserIds());
            assertEquals(11,v2.keys().size());
            var all=f.tx.read(c -> source.contents(v2,v2.keys(),c)); assertEquals(new TreeSet<>(v2.keys()),new TreeSet<>(all.stream().map(SubobjectVersion::key).toList()));
            var one=List.of(v2.keys().getFirst()); assertEquals(1,f.tx.read(c -> source.contents(v2,one,c)).size(),"AUD-V3-013 only requested contents");
            var finance=new ExpenseFinancials(f.p,f.e,new Amount(new Fraction(17,19)),f.a,Map.of(f.b,Optional.empty(),f.c,Optional.of(new Weight(new Fraction(2,3)))));
            f.run(f.command(2,new CreateExpense(new ExpenseDetails(f.p,f.e,new Label("E"),f.date),finance)));
            var e3=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=3",UUID.class);
            var v3=f.tx.read(c -> source.composition(e3,c));
            assertEquals(14,v3.keys().size()); assertEquals(1,v3.expenses().size());
            var all3=f.tx.read(c -> source.contents(v3,v3.keys(),c));
            assertEquals(EnumSet.allOf(SubobjectKind.class),EnumSet.copyOf(all3.stream().map(s -> s.key().kind()).toList()),"all eight kinds");
            assertEquals(finance,all3.stream().map(SubobjectVersion::content).filter(SubobjectContent.ExpenseFinancialsContent.class::isInstance).map(SubobjectContent.ExpenseFinancialsContent.class::cast).findFirst().orElseThrow().value());
            f.run(f.command(3,new UpdateExpenseDetails(f.e,new Label("new label"),f.date.plusDays(1))));
            f.run(f.command(4,new DeleteExpense(f.e))); f.run(f.command(5,new DeletePot()));
            assertEquals(v2,f.tx.read(c -> source.composition(e2,c)),"late active historical version after D");
            assertEquals(all,f.tx.read(c -> source.contents(v2,v2.keys(),c))); assertThrows(RuntimeException.class,() -> f.tx.read(c -> source.composition(UUID.randomUUID(),c)));
            assertEquals(all3,f.tx.read(c -> source.contents(v3,v3.keys(),c)),"historical contents exclude changed activeTo");
            var e4=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=4",UUID.class);
            var v4=f.tx.read(c -> source.composition(e4,c));
            assertEquals(2,v4.expenses().getFirst().detailsVersion()); assertEquals(1,v4.expenses().getFirst().financialsVersion());
            var e5=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=5",UUID.class);
            assertTrue(f.tx.<Boolean>read(c -> source.composition(e5,c).expenses().getFirst().deleted()));
            var probe=new ReadSourceSqlProbe(f.source); var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(probe); manager.setNestedTransactionAllowed(false);
            var owner=new com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions(probe,manager); var observed=new JdbcPotVersionSource(probe);
            owner.read(c -> observed.composition(e3,c));
            assertTrue(probe.statements.stream().noneMatch(q -> q.contains("v3_expense_participant")),"composition does not load participations");
            assertTrue(probe.statements.size()<=13,probe.statements.toString());
            probe.statements.clear(); owner.read(c -> observed.contents(v3,List.of(v3.keys().getFirst()),c));
            assertTrue(probe.statements.stream().noneMatch(q -> q.contains("v3_expense_financials") || q.contains("v3_expense_participant")),"only missing header loaded");
            f.timeline.add("P01-L local SQL count and selectivity="+probe.statements);
            f.timeline.add("P01 historical composition and selective keys="+v2.keys());
        } finally { f.collect(); }
    }
}
