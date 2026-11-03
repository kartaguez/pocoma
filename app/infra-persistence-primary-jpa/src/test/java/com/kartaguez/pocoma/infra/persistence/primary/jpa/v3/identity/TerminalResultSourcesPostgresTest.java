package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
@Testcontainers
class TerminalResultSourcesPostgresTest extends IdentityEngineFixture {
    @Test void durableRequestsAndTerminals() throws Exception {
        try(var pot=new ReadPotPublicationFixture()) {
            var reader=new JdbcCommandTerminalSource(pot.source(),pot.owner()); var outcomes=new ArrayList<String>();
            for(var event:pot.publishTerminals()) {
                var value=pot.owner().read(c -> reader.load(event,c)); outcomes.add(value.writeOutcome());
                assertEquals("fixture-issuer",value.requesterEui().issuer()); assertEquals("creator",value.requesterEui().subject());
                assertEquals(value.writeOutcome().equals("REJECTED")?null:1L,value.resultingPotVersion());
            }
            assertEquals(List.of("CHANGED","UNCHANGED","REJECTED"),outcomes);
            assertThrows(RuntimeException.class,() -> pot.owner().read(c -> reader.load(UUID.randomUUID(),c)));
        }
        var e1=key("requester"); var e2=key("target"); var registration=request(new IdentityRequest.Registration(),e1,null,e1); executeAdmitted(registration); var bound=current(e1);
        var link=request(new IdentityRequest.Link(e2),e1,bound.bindingToken(),e2); executeAdmitted(link);
        var unlink=request(new IdentityRequest.Unlink(e2),e1,bound.bindingToken(),e2); executeAdmitted(unlink);
        var rejected=request(new IdentityRequest.Link(e2),e1,new BindingToken("invalid"),e2); executeAdmitted(rejected);
        var reader=new JdbcIdentityTerminalSource(source,tx);
        for(var request:List.of(registration,link,unlink,rejected)) {
            var event=admin.queryForObject("select event_id from v3_identity_completion_event where subject_id=?",UUID.class,request.requestId());
            var value=tx.read(c -> reader.load(event,c)); assertEquals(e1,value.requesterEui()); assertEquals(request.requestId(),value.requestId());
            if(request==rejected) { assertEquals("REJECTED",value.outcome()); assertNull(value.targetEui()); assertNull(value.targetVersion()); }
            else { assertEquals(request.target(),value.targetEui()); assertNotNull(value.targetVersion()); }
        }
        var event=admin.queryForObject("select event_id from v3_identity_completion_event where subject_id=?",UUID.class,registration.requestId());
        admin.execute("alter table v3_identity_completion_event disable trigger user");
        admin.update("update v3_identity_completion_event set subject_type='LINK' where event_id=?",event);
        assertThrows(RuntimeException.class,() -> tx.read(c -> reader.load(event,c)),"operation mismatch is corruption");
        evidence.add("I7 actual Command/Identity request + terminal, no-op, three operations, requester versus target");
    }
}
