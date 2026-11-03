package com.kartaguez.pocoma.domain.projection.v3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class PotVersionContractTest {
    @Test void compositionAndKeys() {
        var p=UUID.randomUUID(); var u=UUID.randomUUID(); var a=UUID.fromString("80000000-0000-0000-0000-000000000000"); var b=new UUID(0,1);
        var v=new PotVersion(p,7,2,1,false,List.of(new PotVersion.Shareholder(a,1,3,2,false,false),new PotVersion.Shareholder(b,1,1,1,true,true)),List.of(new PotVersion.Expense(UUID.randomUUID(),2,1,4,true)),Set.of(u),Set.of(u));
        assertEquals(b,v.shareholders().getFirst().id(),"A08 unsigned order"); assertEquals(11,v.keys().size());
        assertThrows(UnsupportedOperationException.class,() -> v.authorizedUserIds().clear());
        assertThrows(IllegalArgumentException.class,() -> new PotVersion(p,1,1,1,false,List.of(v.shareholders().getFirst(),v.shareholders().getFirst()),List.of(),Set.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,() -> new PotVersion(p,0,1,1,false,List.of(),List.of(),Set.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,() -> new PotVersion(p,1,1,1,false,List.of(),List.of(),Set.of(),Set.of(u)));
        assertNotEquals(v,new PotVersion(p,7,2,1,true,v.shareholders(),v.expenses(),Set.of(u),Set.of(u)));
        assertEquals(0,v.keys().stream().filter(k -> !k.potId().equals(p)).count());
    }
}
