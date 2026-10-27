package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import java.net.ServerSocket;
import java.sql.Connection;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Supplemental branch probes using the compiled, committed I2 classes. No production repair. */
public final class R9IndependentCorruptionProof {
    static ConsumptionEngine engine(CommandExecutionPostgresTest f) {
        return new ConsumptionEngine(new ConsumptionDefinition(
                L1CommandStore.claim(new UUID(0,1),new UUID(0,2)).consumptionId(),
                ConsumableFamily.COMMAND,ConsumptionDefinition.Authority.WRITE),f.tx,
                new JdbcConsumptionSlotStore(f.source),new ExecutePotCommand(
                    new JdbcPotCommandStore(f.source,f.tx),new JdbcPotCommandObservation(f.source),
                    f.authority,f.history,f.revisions,Clock.systemUTC()));
    }
    static String snapshot(CommandExecutionPostgresTest f,UUID id) throws Exception {
        try(var c=f.source.getConnection()) {
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try(var s=c.prepareStatement("select json_build_object('status',status,'results',(select count(*) from v3_command_result where request_id=c.request_id),'completion',(select count(*) from v3_command_completion_event where subject_id=c.request_id),'events',(select count(*) from v3_pot_event where request_id=c.request_id),'slot',(select count(*) from consumption_slot where consumable_id=c.request_id))::text from v3_command c where request_id=?")) {
                s.setObject(1,id);try(var rs=s.executeQuery()){rs.next();var value=rs.getString(1);c.rollback();return value;}
            }
        }
    }
    static void require(Object expected,Object actual) {if(!Objects.equals(expected,actual))throw new AssertionError("expected="+expected+" actual="+actual);}
    public static void main(String[] args) throws Exception {
        CommandExecutionPostgresTest.POSTGRES.start();
        try {
            for(String variant:List.of("terminal-without-result","pending-with-result","pending-with-event",
                    "terminal-without-completion","changed-without-components","changed-without-position","historical-completed","real-unavailable")) {
                var f=new CommandExecutionPostgresTest();f.setup();f.base();
                try {
                    var command=f.command(2,new PotCommand.UpdatePotDetails(new Label("probe expected V3")));
                    var claim=L1CommandStore.claim(command.requestId(),f.admit(command));var engine=engine(f);
                    if(variant.startsWith("changed-") || variant.equals("terminal-without-completion") || variant.equals("historical-completed"))
                        require(ConsumptionEngine.Status.COMMITTED,engine.execute(claim).status());
                    new TransactionTemplate(new DataSourceTransactionManager(f.source)).executeWithoutResult(status -> {
                        f.jdbc.execute("set local session_replication_role=replica");
                        switch(variant) {
                            case "terminal-without-result" -> f.jdbc.update("update v3_command set status='TERMINAL' where request_id=?",claim.consumableId());
                            case "pending-with-result" -> f.jdbc.update("insert into v3_command_result(request_id,outcome,rejection) values (?,'REJECTED','fixture-corruption')",claim.consumableId());
                            case "pending-with-event" -> f.jdbc.update("insert into v3_pot_event(event_id,pot_id,subject_version,request_id,recorded_at) values (?,?,99,?,clock_timestamp())",new UUID(43,1),f.p.value(),claim.consumableId());
                            case "terminal-without-completion" -> f.jdbc.update("delete from v3_command_completion_event where subject_id=?",claim.consumableId());
                            case "changed-without-components" -> f.jdbc.update("delete from v3_pot_event_component where event_id in(select event_id from v3_pot_event where request_id=?)",claim.consumableId());
                            case "changed-without-position" -> f.jdbc.update("delete from v3_event_position where event_id in(select event_id from v3_pot_event where request_id=?)",claim.consumableId());
                            default -> {}
                        }
                    });
                    if(variant.equals("historical-completed")) {
                        var next=f.command(3,new PotCommand.UpdatePotDetails(new Label("probe expected V4")));
                        require(ConsumptionEngine.Status.COMMITTED,engine.execute(L1CommandStore.claim(next.requestId(),f.admit(next))).status());
                    }
                    var before=snapshot(f,claim.consumableId());var expected=variant.equals("historical-completed")?RecoveryObservation.COMPLETED:RecoveryObservation.INCONSISTENT;
                    RecoveryObservation actual;
                    if(variant.equals("real-unavailable")) {
                        int unused;try(var listener=new ServerSocket(0)){unused=listener.getLocalPort();}
                        var absent=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+unused+"/absent?connectTimeout=1","fixture","fixture");
                        var manager=new DataSourceTransactionManager(absent);manager.setNestedTransactionAllowed(false);
                        var owner=new SpringAttemptTransactions(absent,manager);
                        var bad=new ConsumptionEngine(new ConsumptionDefinition(claim.consumptionId(),ConsumableFamily.COMMAND,ConsumptionDefinition.Authority.WRITE),owner,new JdbcConsumptionSlotStore(absent),new ExecutePotCommand(new JdbcPotCommandStore(absent,owner),new JdbcPotCommandObservation(absent),f.authority,f.history,f.revisions,Clock.systemUTC()));
                        actual=bad.observe(claim);expected=RecoveryObservation.UNAVAILABLE;
                    }else actual=engine.observe(claim);
                    require(expected,actual);require(before,snapshot(f,claim.consumableId()));
                    System.out.println("R9 SUPPLEMENT PASS "+variant+" expected="+expected+" oracle="+before+"; observation changed zero durable rows");
                }finally{f.threads.shutdownNow();}
            }
        }finally{CommandExecutionPostgresTest.POSTGRES.stop();}
    }
}
