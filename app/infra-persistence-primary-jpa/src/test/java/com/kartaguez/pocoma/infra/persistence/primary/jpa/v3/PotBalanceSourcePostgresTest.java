package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
@Testcontainers
class PotBalanceSourcePostgresTest {
    @Container static final PostgreSQLContainer PG=CommandExecutionPostgresTest.POSTGRES;
    @Test void exactHistoricalFinance() throws Exception {
        var f=new CommandExecutionPostgresTest(); f.setup();
        try {
            f.base(); var details=new ExpenseDetails(f.p,f.e,new Label("E"),f.date);
            var finance=new ExpenseFinancials(f.p,f.e,new Amount(new Fraction(17,19)),f.a,Map.of(f.b,Optional.empty(),f.c,Optional.of(new Weight(new Fraction(2,3)))));
            f.run(f.command(2,new CreateExpense(details,finance)));
            UUID event=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=3",UUID.class);
            var source=new JdbcPotBalanceSource(f.source); var state=f.tx.read(c -> source.load(event,c));
            var actual=new PotBalanceCalculator().calculate(state).orElseThrow();
            assertEquals(new Fraction(17,19),actual.values().get(f.a)); assertEquals(new Fraction(-51,95),actual.values().get(f.b)); assertEquals(new Fraction(-34,95),actual.values().get(f.c));
            f.run(f.command(3,new UpdateShareholderWeights(List.of(new ShareholderTransitions.WeightIntent(f.b,new Weight(new Fraction(2,1)))))));
            var weightEvent=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=4",UUID.class);
            var weightsChanged=new PotBalanceCalculator().calculate(f.tx.read(c -> source.load(weightEvent,c))).orElseThrow();
            assertEquals(new Fraction(-51,76),weightsChanged.values().get(f.b),"P02-C historical default weight");
            assertEquals(new Fraction(-17,76),weightsChanged.values().get(f.c));
            f.run(f.command(4,new UpdatePotDetails(new Label("nonfinancial"))));
            f.run(f.command(5,new DeleteExpense(f.e))); f.run(f.command(6,new DeletePot()));
            assertEquals(state,f.tx.read(c -> source.load(event,c)),"WRITE current never replaces historical facts");
            var terminal=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=7",UUID.class);
            assertTrue(new PotBalanceCalculator().calculate(f.tx.read(c -> source.load(terminal,c))).isEmpty());
            var nonfinancial=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=5",UUID.class);
            assertEquals(weightsChanged.values(),new PotBalanceCalculator().calculate(f.tx.read(c -> source.load(nonfinancial,c))).orElseThrow().values());
            var deletion=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=6",UUID.class);
            assertTrue(new PotBalanceCalculator().calculate(f.tx.read(c -> source.load(deletion,c))).orElseThrow().values().values().stream().allMatch(Fraction.ZERO::equals));
            var probe=new ReadSourceSqlProbe(f.source); var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(probe); manager.setNestedTransactionAllowed(false);
            var owner=new com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions(probe,manager); var observed=new JdbcPotBalanceSource(probe);
            owner.read(c -> observed.load(event,c));
            assertTrue(probe.statements.stream().noneMatch(q -> q.contains("v3_shareholder_details") || q.contains("v3_expense_details")),"no unrelated details or user attachments");
            assertEquals(1,probe.statements.stream().filter(q -> q.contains("v3_expense_participant")).count(),"one grouped participation read");
            f.timeline.add("P02-M local emitted SQL="+probe.statements);
            f.timeline.add("P02-A/B/D/E/H exact independent literals and D");
        } finally { f.collect(); }
    }
}
