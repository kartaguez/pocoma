package com.kartaguez.pocoma.engine.read.identity;
import com.kartaguez.pocoma.contract.readquery.BindingRead;
import com.kartaguez.pocoma.domain.identity.*;
import com.kartaguez.pocoma.domain.identity.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class CurrentBindingContractTest {
    @Test void euiStateAndMinVersion() {
        var e1=new ExternalIdentity("issuer","one"); var e2=new ExternalIdentity("issuer","two");
        var id=new ExternalUserIdentityId(UUID.randomUUID()); var user=new PocomaUserId(UUID.randomUUID());
        var state=new ExternalUserIdentity(id,e1,3,user,new BindingToken("private-fixture-token"));
        var get=new GetCurrentBinding(e -> e.equals(e1)?Optional.of(state):Optional.empty());
        assertEquals(BindingRead.Status.AVAILABLE,get.get(e1,2).status());
        assertEquals("private-fixture-token",get.get(e1,3).value().bindingToken());
        assertEquals(BindingRead.Status.NOT_READY,get.get(e1,4).status()); assertNull(get.get(e1,4).value());
        assertEquals(BindingRead.Status.ABSENT,get.get(e2,0).status());
        assertFalse(get.get(e1,0).toString().contains("private-fixture-token"));
        var free=new GetCurrentBinding(e -> Optional.of(new ExternalUserIdentity(id,e1,4,null,null)));
        assertFalse(free.get(e1,4).value().active()); assertNull(free.get(e1,4).value().bindingToken());
        assertThrows(IllegalArgumentException.class,() -> new ExternalUserIdentity(id,e1,1,user,null));
        assertThrows(IllegalStateException.class,() -> new GetCurrentBinding(e -> Optional.of(state)).get(e2,0));
    }
}
