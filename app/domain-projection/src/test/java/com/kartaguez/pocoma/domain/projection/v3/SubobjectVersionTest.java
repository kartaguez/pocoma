package com.kartaguez.pocoma.domain.projection.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.v3.SubobjectContent.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubobjectVersionTest {
    final UUID p = new UUID(0, 1), s = new UUID(0, 2), e = new UUID(0, 3);
    final PotId pot = new PotId(p);
    final ShareholderId sid = new ShareholderId(s);
    final ExpenseId eid = new ExpenseId(e);
    final UserId user = new UserId(new UUID(0, 4));

    List<SubobjectContent> contents() {
        return List.of(new PotHeaderContent(new PotHeader(pot, false)),
                new PotDetailsContent(new PotDetails(pot, new Label(""), user)),
                new ShareholderHeaderContent(new ShareholderHeader(pot, sid, false)),
                new ShareholderDetailsContent(new ShareholderDetails(pot, sid, new Name("A"), Optional.empty())),
                new ShareholderWeightContent(new ShareholderWeight(pot, sid, new Weight(new Fraction(1, 2)))),
                new ExpenseHeaderContent(new ExpenseHeader(pot, eid, false)),
                new ExpenseDetailsContent(new ExpenseDetails(pot, eid, new Label("E"), LocalDate.of(2026, 11, 2))),
                new ExpenseFinancialsContent(new ExpenseFinancials(pot, eid, new Amount(Fraction.ZERO), sid, Map.of(sid, Optional.empty()))));
    }
    SubobjectVersion version(SubobjectContent c) {
        return new SubobjectVersion(new SubobjectKey(c.potId(), c.kind(), c.objectId(), 1), c);
    }
    @Test void keyAndKind() {
        var contents = contents();
        assertEquals(8, SubobjectContent.class.getPermittedSubclasses().length);
        assertEquals(EnumSet.allOf(SubobjectKind.class), contents.stream().map(SubobjectContent::kind).collect(java.util.stream.Collectors.toSet()));
        for (var c : contents) {
            assertEquals(c, version(c).content());
            assertThrows(IllegalArgumentException.class, () -> new SubobjectKey(p, c.kind(), c.objectId(), 0));
            assertThrows(IllegalArgumentException.class, () -> new SubobjectKey(p, c.kind(), c.objectId(), -1));
            assertDoesNotThrow(() -> new SubobjectKey(p, c.kind(), c.objectId(), Long.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> new SubobjectVersion(new SubobjectKey(new UUID(0, 9), c.kind(),
                    c.kind().rank() < 2 ? new UUID(0, 9) : c.objectId(), 1), c));
            var wrongKind = c.kind() == SubobjectKind.EXPENSE_HEADER ? SubobjectKind.EXPENSE_DETAILS : SubobjectKind.EXPENSE_HEADER;
            assertThrows(IllegalArgumentException.class, () -> new SubobjectVersion(new SubobjectKey(p, wrongKind, c.objectId(), 1), c));
        }
        assertThrows(IllegalArgumentException.class, () -> new SubobjectKey(p, SubobjectKind.POT_HEADER, s, 1));
        assertThrows(IllegalArgumentException.class, () -> new SubobjectKey(p, SubobjectKind.POT_DETAILS, s, 1));
        assertThrows(NullPointerException.class, () -> new SubobjectKey(null, SubobjectKind.POT_HEADER, p, 1));
        assertThrows(NullPointerException.class, () -> new SubobjectKey(p, null, p, 1));
        assertThrows(NullPointerException.class, () -> new SubobjectVersion(null, contents.getFirst()));
    }
    @Test void allFieldsMatter() {
        var originals = contents();
        var changes = List.<SubobjectContent>of(
                new PotHeaderContent(new PotHeader(pot, true)),
                new PotDetailsContent(new PotDetails(pot, new Label(" "), user)),
                new PotDetailsContent(new PotDetails(pot, new Label(""), new UserId(e))),
                new ShareholderHeaderContent(new ShareholderHeader(pot, sid, true)),
                new ShareholderDetailsContent(new ShareholderDetails(pot, sid, new Name("a"), Optional.empty())),
                new ShareholderDetailsContent(new ShareholderDetails(pot, sid, new Name("A"), Optional.of(user))),
                new ShareholderWeightContent(new ShareholderWeight(pot, sid, new Weight(Fraction.ONE))),
                new ExpenseHeaderContent(new ExpenseHeader(pot, eid, true)),
                new ExpenseDetailsContent(new ExpenseDetails(pot, eid, new Label("e"), LocalDate.of(2026, 11, 2))),
                new ExpenseDetailsContent(new ExpenseDetails(pot, eid, new Label("E"), LocalDate.of(2026, 11, 3))),
                new ExpenseFinancialsContent(new ExpenseFinancials(pot, eid, new Amount(Fraction.ONE), sid, Map.of(sid, Optional.empty()))),
                new ExpenseFinancialsContent(new ExpenseFinancials(pot, eid, new Amount(Fraction.ZERO), new ShareholderId(e), Map.of(sid, Optional.empty()))),
                new ExpenseFinancialsContent(new ExpenseFinancials(pot, eid, new Amount(Fraction.ZERO), sid, Map.of(new ShareholderId(e), Optional.empty()))),
                new ExpenseFinancialsContent(new ExpenseFinancials(pot, eid, new Amount(Fraction.ZERO), sid, Map.of(sid, Optional.of(new Weight(Fraction.ONE))))));
        for (var changed : changes) {
            var original = originals.get(changed.kind().rank());
            assertNotEquals(version(original), version(changed), changed.kind().name());
        }
        assertEquals(contents(), originals);
        for (var c : originals) {
            var v = version(c);
            assertEquals(v.hashCode(), version(c).hashCode());
            assertNotEquals(v, new SubobjectVersion(new SubobjectKey(p, c.kind(), c.objectId(), 2), c));
        }
    }
    @Test void detachedFinancialParticipants() {
        var map = new HashMap<ShareholderId, Optional<Weight>>();
        map.put(sid, Optional.empty());
        var financials = new ExpenseFinancials(pot, eid, new Amount(Fraction.ZERO), sid, map);
        map.clear();
        assertEquals(Map.of(sid, Optional.empty()), financials.participants());
        assertThrows(UnsupportedOperationException.class, () -> financials.participants().clear());
        var entry = new ExpenseFinancials.Participation(sid, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> ExpenseFinancials.of(pot, eid, new Amount(Fraction.ZERO), sid, List.of(entry, entry)));
    }
    @Test void postgresUuidOrder() {
        var a = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        var b = UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertTrue(a.compareTo(b) > 0); // discriminates Java's signed comparison
        assertTrue(SubobjectKey.compareUuid(a, b) < 0);
        assertTrue(SubobjectKey.compareUuid(new UUID(0, Long.MAX_VALUE), new UUID(0, Long.MIN_VALUE)) < 0);
        assertTrue(new SubobjectKey(a, SubobjectKind.EXPENSE_FINANCIALS, e, 2).compareTo(new SubobjectKey(b, SubobjectKind.POT_HEADER, b, 1)) < 0);
        assertTrue(new SubobjectKey(p, SubobjectKind.POT_DETAILS, p, 1).compareTo(new SubobjectKey(p, SubobjectKind.SHAREHOLDER_HEADER, s, 1)) < 0);
        assertTrue(new SubobjectKey(p, SubobjectKind.EXPENSE_HEADER, a, 9).compareTo(new SubobjectKey(p, SubobjectKind.EXPENSE_HEADER, b, 1)) < 0);
    }
}
