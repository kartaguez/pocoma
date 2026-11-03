package com.kartaguez.pocoma.engine.consume.projectiontask.v3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class PotVisibilityRulesTest {
    @Test void visibleSetRules() {
        var c=UUID.randomUUID(); var u=UUID.randomUUID(); var other=UUID.randomUUID();
        var a=new PotVisibilityRules.Attachment(false,u); var detached=new PotVisibilityRules.Attachment(false,null);
        assertEquals(Set.of(c,u),PotVisibilityRules.visible(c,false,List.of(a,a,detached)));
        assertEquals(Set.of(c,u),PotVisibilityRules.visible(c,false,List.of(a,new PotVisibilityRules.Attachment(true,u))));
        assertEquals(Set.of(c),PotVisibilityRules.visible(c,false,List.of(new PotVisibilityRules.Attachment(true,u),detached)));
        assertEquals(Set.of(),PotVisibilityRules.visible(c,true,List.of(a)));
        assertFalse(PotVisibilityRules.visible(c,false,List.of(a)).contains(other));
    }
}
