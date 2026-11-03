package com.kartaguez.pocoma.engine.read.currentbinding.v3;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class CurrentBindingV3ContractTest {
    @Test void euiStateAndMinVersion() {
        var e1=new ExternalIdentity("issuer","one"); var e2=new ExternalIdentity("issuer","two");
        var id=new ExternalUserIdentityId(UUID.randomUUID()); var user=new PocomaUserId(UUID.randomUUID());
        var state=new ExternalUserIdentity(id,e1,3,user,new BindingToken("private-fixture-token"));
        var get=new GetCurrentBindingV3(e -> e.equals(e1)?Optional.of(state):Optional.empty());
        assertEquals(GetCurrentBindingV3.Status.AVAILABLE,get.get(e1,2).status());
        assertEquals("private-fixture-token",get.get(e1,3).value().bindingToken());
        assertEquals(GetCurrentBindingV3.Status.NOT_READY,get.get(e1,4).status()); assertNull(get.get(e1,4).value());
        assertEquals(GetCurrentBindingV3.Status.ABSENT,get.get(e2,0).status());
        assertFalse(get.get(e1,0).toString().contains("private-fixture-token"));
        var free=new GetCurrentBindingV3(e -> Optional.of(new ExternalUserIdentity(id,e1,4,null,null)));
        assertFalse(free.get(e1,4).value().active()); assertNull(free.get(e1,4).value().bindingToken());
        assertThrows(IllegalArgumentException.class,() -> new ExternalUserIdentity(id,e1,1,user,null));
        assertThrows(IllegalStateException.class,() -> new GetCurrentBindingV3(e -> Optional.of(state)).get(e2,0));
    }
}
