package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.kartaguez.pocoma.domain.projection.v3.CommandTerminalResult;
import com.kartaguez.pocoma.domain.projection.v3.IdentityTerminalResult;
import com.kartaguez.pocoma.engine.read.commandresult.v3.*;
import com.kartaguez.pocoma.engine.read.registrationresult.v3.*;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
@Testcontainers
class TerminalResultsReadStorePostgresTest {
    @Container static final PostgreSQLContainer PG=ReadStoreFixture.container();
    final ExternalIdentity e1=new ExternalIdentity("issuer","one"),e2=new ExternalIdentity("issuer","two"),e3=new ExternalIdentity("issuer","three");
    CommandTerminalResult command(UUID id,String outcome) { return new CommandTerminalResult(id,"UpdatePotDetails",e1,new UUID(1,1),outcome,outcome.equals("REJECTED")?null:7L,outcome.equals("REJECTED")?"INVALID_BINDING":null); }
    IdentityTerminalResult identity(UUID id,String op,boolean changed) { return new IdentityTerminalResult(id,op,e1,changed?"CHANGED":"REJECTED",changed?(op.equals("REGISTRATION")?e1:e2):null,changed?3L:null,changed?null:"INVALID_BINDING"); }
    @Test void mappingsAndOwnership() throws Exception {
        var f=new ReadStoreFixture(PG,"7"); var cs=new JdbcCommandTerminalResultStore(f.source,f.tx); var is=new JdbcIdentityTerminalResultStore(f.source,f.tx);
        var cg=new GetCommandTerminalResult(cs); var ig=new GetIdentityTerminalResult(is);
        var pending=UUID.randomUUID(); assertTrue(f.tx.read(c -> cg.get(pending,e1)).isEmpty(),"A10-H absent before materialization");
        for(var outcome:List.of("CHANGED","UNCHANGED","REJECTED")) {
            var value=command(UUID.randomUUID(),outcome); f.write(() -> { cs.insertOrVerify(value); cs.requireStored(value); });
            assertEquals(value,f.tx.read(c -> cg.get(value.requestId(),e1).orElseThrow()));
            assertTrue(f.tx.read(c -> cg.get(value.requestId(),e2)).isEmpty()); assertTrue(f.tx.read(c -> cg.get(value.requestId(),e3)).isEmpty());
            assertTrue(f.tx.read(c -> cg.get(UUID.randomUUID(),e2)).isEmpty());
        }
        for(var op:List.of("REGISTRATION","LINK","UNLINK")) for(boolean changed:List.of(true,false)) {
            var value=identity(UUID.randomUUID(),op,changed); f.write(() -> { is.insertOrVerify(value); is.requireStored(value); });
            assertEquals(value,f.tx.read(c -> ig.get(value.requestId(),e1).orElseThrow())); assertTrue(f.tx.read(c -> ig.get(value.requestId(),e2)).isEmpty());
            assertEquals(value,f.tx.read(c -> ig.get(value.requestId(),e1).orElseThrow()),"A10-F/K no binding dependency or expiry");
        }
        f.oracle.execute("revoke select on read_command_result from "+f.runtimeRole);
        assertThrows(RuntimeException.class,() -> f.tx.read(c -> cg.get(pending,e1)),"A10-I unavailable != absent");
        f.evidence("A10-A..K internal",Map.of("command",3,"identity",6));
    }
    @Test void concurrentImmutableResults() throws Exception {
        var f=new ReadStoreFixture(PG,"7"); var cs=new JdbcCommandTerminalResultStore(f.source,f.tx); var is=new JdbcIdentityTerminalResultStore(f.source,f.tx);
        var c=command(UUID.randomUUID(),"CHANGED"); var i=identity(UUID.randomUUID(),"LINK",true);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1); var tasks=new ArrayList<Future<Boolean>>();
            for(int n=0;n<2;n++) tasks.add(pool.submit(() -> { start.await(); f.write(() -> { cs.insertOrVerify(c); is.insertOrVerify(i); }); return true; }));
            start.countDown(); for(var task:tasks) assertTrue(task.get(15,TimeUnit.SECONDS));
        }
        var badCommands=List.of(new CommandTerminalResult(c.requestId(),"DeletePot",e1,c.potId(),"CHANGED",7L,null),new CommandTerminalResult(c.requestId(),c.operation(),e2,c.potId(),"CHANGED",7L,null),new CommandTerminalResult(c.requestId(),c.operation(),e1,UUID.randomUUID(),"CHANGED",7L,null),command(c.requestId(),"REJECTED"),new CommandTerminalResult(c.requestId(),c.operation(),e1,c.potId(),"CHANGED",8L,null));
        for(var bad:badCommands) assertThrows(RuntimeException.class,() -> f.write(() -> cs.insertOrVerify(bad)));
        for(var bad:List.of(new IdentityTerminalResult(i.requestId(),"UNLINK",e1,"CHANGED",e2,3L,null),new IdentityTerminalResult(i.requestId(),"LINK",e3,"CHANGED",e2,3L,null),new IdentityTerminalResult(i.requestId(),"LINK",e1,"CHANGED",e3,3L,null),new IdentityTerminalResult(i.requestId(),"LINK",e1,"CHANGED",e2,4L,null),identity(i.requestId(),"LINK",false)))
            assertThrows(RuntimeException.class,() -> f.write(() -> is.insertOrVerify(bad)));
        assertEquals(c,f.tx.read(x -> cs.find(c.requestId()).orElseThrow())); assertEquals(i,f.tx.read(x -> is.find(i.requestId()).orElseThrow()));
        f.evidence("A10-J immutable races full equality",List.of(c,i));
    }
    @Test void migrationsAndPrivileges() throws Exception {
        var f=new ReadStoreFixture(PG,"6"); assertEquals(1,f.migrate("7"));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_command_result','UPDATE,DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        assertFalse(f.oracle.queryForObject("select has_table_privilege(?,'read_identity_request_result','UPDATE,DELETE,TRUNCATE')",Boolean.class,f.runtimeRole));
        var cs=new JdbcCommandTerminalResultStore(f.source,f.tx); var value=command(UUID.randomUUID(),"REJECTED"); f.write(() -> cs.insertOrVerify(value));
        assertThrows(RuntimeException.class,() -> f.oracle.execute("truncate read_command_result"));
        assertThrows(RuntimeException.class,() -> cs.find(value.requestId()),"no autonomous result read");
        f.evidence("migrations terminal forms",value);
    }
}
