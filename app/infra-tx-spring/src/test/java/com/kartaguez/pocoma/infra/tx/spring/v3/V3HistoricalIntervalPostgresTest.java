package com.kartaguez.pocoma.infra.tx.spring.v3;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Tests a generic interval fixture, not SPEC-01's unimplemented Pot domain. */
class V3HistoricalIntervalPostgresTest extends PostgresFoundationTestSupport {
    @Test
    void resolvesSemiOpenBoundariesAndOpenEndInAnyOrder() {
        UUID id = UUID.randomUUID();
        revision(id, 1, 2, 5L, "alpha");
        revision(id, 2, 5, 9L, "beta");
        revision(id, 3, 9, null, "gamma");
        Map<Long, String> expected = Map.of(2L, "alpha", 4L, "alpha", 5L, "beta", 8L, "beta", 9L, "gamma", 100L, "gamma");
        for (long version : new long[] {100, 5, 2, 9, 4, 8}) assertEquals(expected.get(version), exact(id, version));
        assertTrue(values(id, 1).isEmpty(), "Absence before creation is normal");
    }

    @Test
    void closesAndOpensAtomicallyAndRollbackPreservesHistory() {
        UUID id = UUID.randomUUID();
        revision(id, 1, 1, null, "original");
        assertThrows(IllegalStateException.class, () -> write.runner.runInTransaction(() -> {
            assertEquals(1, write.jdbc.update("update component_revision set active_to=4 where component_id=? and local_version=1", id));
            revision(id, 2, 4, null, "aborted");
            throw new IllegalStateException("injected rollback");
        }));
        assertEquals("original", exact(id, 4));
        assertEquals(1, write.jdbc.queryForObject("select count(*) from component_revision", Integer.class));
        write.runner.runInTransaction(() -> {
            assertEquals(1, write.jdbc.update("update component_revision set active_to=4 where component_id=? and local_version=1", id));
            revision(id, 2, 4, null, "committed");
            return null;
        });
        assertEquals("original", exact(id, 3));
        assertEquals("committed", exact(id, 4));
    }

    @Test
    void rejectsInvalidIntervalsAndDuplicateOpenVersions() {
        UUID id = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () -> revision(id, 1, 4, 4L, "empty"));
        assertThrows(DataIntegrityViolationException.class, () -> revision(id, 1, 4, 3L, "inverted"));
        revision(id, 1, 1, null, "open");
        assertThrows(DataIntegrityViolationException.class, () -> revision(id, 2, 2, null, "second-open"));
        assertEquals("open", exact(id, 2));
    }

    @Test
    void reportsGapsAndOverlapsInsteadOfFallingBackToCurrent() {
        UUID gap = UUID.randomUUID();
        revision(gap, 1, 1, 3L, "old");
        revision(gap, 2, 4, null, "current");
        assertThrows(IllegalStateException.class, () -> exact(gap, 3));
        UUID overlap = UUID.randomUUID();
        revision(overlap, 1, 1, 6L, "old");
        revision(overlap, 2, 4, null, "current");
        assertThrows(IllegalStateException.class, () -> exact(overlap, 4));
        assertEquals("current", exact(overlap, 6));
    }

    private void revision(UUID id, long local, long from, Long to, String payload) {
        write.jdbc.update("insert into component_revision values (?,?,?,?,?)", id, local, from, to, payload);
    }

    private java.util.List<String> values(UUID id, long version) {
        return write.jdbc.queryForList("select payload from component_revision where component_id=? and active_from<=? "
                + "and (active_to is null or ?<active_to)", String.class, id, version, version);
    }

    private String exact(UUID id, long version) {
        var found = values(id, version);
        if (found.size() != 1) throw new IllegalStateException("Expected exactly one applicable revision, found " + found.size());
        return found.getFirst();
    }
}
