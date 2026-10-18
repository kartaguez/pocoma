package com.kartaguez.pocoma.domain.pot.aggregate;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

class ShareholderIdentityCollisionTest {
    @Test void generatedCollisionCannotReplaceExistingOrEarlierAddedShareholder() {
        var pot = new PotShareholders(PotId.of(UUID.randomUUID()), Set.of());
        var id = ShareholderId.of(UUID.randomUUID());
        var original = pot.addShareholder(Name.of("first"), Weight.of(Fraction.of(1, 1)), null, () -> id);
        assertThrows(IllegalStateException.class,
            () -> pot.addShareholder(Name.of("replacement"), Weight.of(Fraction.of(2, 1)), null, () -> id));
        assertSame(original, pot.shareholders().get(id));
        assertEquals(Set.of(id), pot.addedShareholderIds());
        var reconstituted = PotShareholders.reconstitute(pot.potId(), Set.of(original));
        assertThrows(IllegalStateException.class,
            () -> reconstituted.addShareholder(Name.of("replacement"), Weight.of(Fraction.of(2, 1)), null, () -> id));
        assertSame(original, reconstituted.shareholders().get(id));
        assertTrue(reconstituted.addedShareholderIds().isEmpty());
    }
}
