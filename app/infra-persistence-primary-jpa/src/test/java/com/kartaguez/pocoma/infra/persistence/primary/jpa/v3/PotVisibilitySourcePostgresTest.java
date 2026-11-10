package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import com.kartaguez.pocoma.domain.pot.v3.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.value.Name;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
@Testcontainers
class PotVisibilitySourcePostgresTest {
    @Container static final PostgreSQLContainer PG=CommandExecutionPostgresTest.POSTGRES;
    @Test void exactHistoricalMembership() throws Exception {
        var f=new CommandExecutionPostgresTest(); f.setup();
        try {
            f.base();
            // Historical pre-existing attachment fixture, as in L1; no invitation proof claimed.
            f.jdbc.update("update v3_shareholder_details set user_id=? where object_id=?",f.member.value(),f.a.value());
            var source=new JdbcPotVisibilitySource(f.source);
            UUID event=f.jdbc.queryForObject("select event_id from v3_pot_event where subject_version=2",UUID.class);
            assertEquals(Set.of(f.creator.value(),f.member.value()),f.tx.read(c -> source.load(event,c).users()));
            assertEquals(Set.of(f.creator.value()),f.tx.read(c -> source.at(f.p.value(),1,c).users()));
            f.run(f.command(2,new UpdateShareholderDetails(List.of(new ShareholderTransitions.DetailsIntent(f.a,new Name("A"),Optional.empty())))));
            assertEquals(Set.of(f.creator.value(),f.member.value()),f.tx.read(c -> source.at(f.p.value(),3,c).users()),"P03-D one attachment remains");
            f.run(f.command(3,new UpdateShareholderDetails(List.of(new ShareholderTransitions.DetailsIntent(f.b,new Name("B"),Optional.empty())))));
            assertEquals(Set.of(f.creator.value()),f.tx.read(c -> source.at(f.p.value(),4,c).users()),"P03-D last attachment removed");
            f.run(f.command(4,new DeletePot()));
            assertEquals(Set.of(),f.tx.read(c -> source.at(f.p.value(),5,c).users()));
            assertEquals(Set.of(f.creator.value(),f.member.value()),f.tx.read(c -> source.load(event,c).users()),"late visibility uses V");
            assertThrows(RuntimeException.class,() -> f.tx.read(c -> source.load(UUID.randomUUID(),c)));
            var probe=new ReadSourceSqlProbe(f.source); var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(probe); manager.setNestedTransactionAllowed(false);
            var owner=new com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions(probe,manager); var observed=new JdbcPotVisibilitySource(probe);
            owner.read(c -> observed.load(event,c));
            assertTrue(probe.statements.stream().noneMatch(q -> q.contains("expense")),"Visibility never loads Expenses");
            f.timeline.add("P03 local membership SQL="+probe.statements);
            f.timeline.add("P03 creator nonShareholder, attachment, D and late V");
        } finally { f.collect(); }
    }
}
