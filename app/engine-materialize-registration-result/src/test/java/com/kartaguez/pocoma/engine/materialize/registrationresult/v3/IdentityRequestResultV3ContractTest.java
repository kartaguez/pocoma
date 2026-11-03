package com.kartaguez.pocoma.engine.materialize.registrationresult.v3;
import com.kartaguez.pocoma.engine.read.registrationresult.v3.IdentityTerminalResult;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class IdentityRequestResultV3ContractTest {
    @Test void terminalContract() {
        var id=UUID.randomUUID(); var e=new ExternalIdentity("i","initiator"); var target=new ExternalIdentity("i","target");
        for(var op:List.of("REGISTRATION","LINK","UNLINK")) {
            var result=new IdentityTerminalResult(id,op,e,"CHANGED",op.equals("REGISTRATION")?e:target,2L,null);
            assertEquals("COMPLETED",result.outcome()); assertEquals(e,result.requesterEui());
            assertEquals("REJECTED",new IdentityTerminalResult(id,op,e,"REJECTED",null,null,"INVALID_BINDING").outcome());
        }
        for(var bad:List.of("UNCHANGED","FAILED","PENDING","EXPIRED")) assertThrows(IllegalArgumentException.class,() -> new IdentityTerminalResult(id,"LINK",e,bad,target,1L,null));
        assertThrows(IllegalArgumentException.class,() -> new IdentityTerminalResult(id,"REGISTRATION",e,"CHANGED",target,1L,null));
        assertThrows(IllegalArgumentException.class,() -> new IdentityTerminalResult(id,"LINK",e,"REJECTED",target,1L,"bad"));
    }
}
