package com.kartaguez.pocoma.engine.materialize.commandresult.v3;
import com.kartaguez.pocoma.domain.projection.v3.CommandTerminalResult;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class CommandResultV3ContractTest {
    @Test void terminalContract() {
        var id=UUID.randomUUID(); var pot=UUID.randomUUID(); var e=new ExternalIdentity("i","s");
        for(var outcome:List.of("CHANGED","UNCHANGED","REJECTED")) {
            var v=new CommandTerminalResult(id,"UpdatePotDetails",e,pot,outcome,outcome.equals("REJECTED")?null:7L,outcome.equals("REJECTED")?"INVALID_BINDING":null);
            assertEquals(outcome.equals("REJECTED")?"REJECTED":"COMPLETED",v.outcome());
            assertNotEquals(v,new CommandTerminalResult(id,"UpdatePotDetails",new ExternalIdentity("i","other"),pot,outcome,v.resultingPotVersion(),v.rejection()));
        }
        for(var bad:List.of("FAILED","PENDING","EXPIRED")) assertThrows(IllegalArgumentException.class,() -> new CommandTerminalResult(id,"CreatePot",e,pot,bad,null,null));
        assertThrows(IllegalArgumentException.class,() -> new CommandTerminalResult(id,"CreatePot",e,pot,"REJECTED",1L,"bad"));
    }
}
