package com.kartaguez.pocoma.infra.postgres.write.v3;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import javax.sql.DataSource;
import java.util.*;
/** Test-only public bridge to the existing Command producer fixture; no new test methods. */
public final class ReadPotPublicationFixture implements AutoCloseable {
    private final CommandExecutionPostgresTest f=new CommandExecutionPostgresTest();
    public ReadPotPublicationFixture() { CommandExecutionPostgresTest.POSTGRES.start(); f.setup(); }
    public DataSource source() { return f.source; }
    public SpringAttemptTransactions owner() { return f.tx; }
    public List<UUID> publishTerminals() {
        var changed=f.command(0,new CreatePot(new Label("P"))); f.run(changed);
        var unchanged=f.command(1,new UpdatePotDetails(new Label("P"))); f.run(unchanged);
        var rejected=f.command(f.eui,"wrong",1,new UpdatePotDetails(new Label("denied"))); f.run(rejected);
        return List.of(changed,unchanged,rejected).stream().map(c -> f.jdbc.queryForObject("select event_id from v3_command_completion_event where subject_id=?",UUID.class,c.requestId())).toList();
    }
    public void close() throws Exception { try { f.collect(); } finally { CommandExecutionPostgresTest.POSTGRES.stop(); } }
}
