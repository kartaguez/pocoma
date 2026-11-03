package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
@Testcontainers
class CurrentBindingSourcePostgresTest extends IdentityEngineFixture {
    @Test void exactEuiHistory() {
        var e1=key("initiator"); var e2=key("target");
        executeAdmitted(request(new IdentityRequest.Registration(),e1,null,e1)); var a=current(e1);
        executeAdmitted(request(new IdentityRequest.Link(e2),e1,a.bindingToken(),e2)); var b=current(e2);
        executeAdmitted(request(new IdentityRequest.Unlink(e2),e1,a.bindingToken(),e2));
        executeAdmitted(request(new IdentityRequest.Link(e2),e1,a.bindingToken(),e2)); var b3=current(e2);
        var reader=new JdbcCurrentBindingSource(source,tx);
        for(var v:List.of(3L,1L,2L)) {
            var event=admin.queryForObject("select event_id from v3_identity_event where subject_type='EXTERNAL_USER_IDENTITY' and subject_id=? and subject_version=?",UUID.class,b.id().value(),v);
            var actual=tx.read(c -> reader.load(event,c).orElseThrow());
            assertEquals(e2,actual.identity()); assertEquals(v,actual.version());
            if(v==1) assertEquals(b,actual); if(v==2) assertNull(actual.bindingToken()); if(v==3) assertEquals(b3,actual);
            assertFalse(actual.toString().contains(b.bindingToken().value()));
        }
        var userEvent=admin.queryForObject("select event_id from v3_identity_event where subject_type='POCOMA_USER'",UUID.class);
        assertTrue(tx.read(c -> reader.load(userEvent,c)).isEmpty());
        assertThrows(RuntimeException.class,() -> tx.read(c -> reader.load(UUID.randomUUID(),c)));
        evidence.add("I6 exact EUI local history, target/initiator, POCOMA_USER filter, no token diagnostics");
    }
}
