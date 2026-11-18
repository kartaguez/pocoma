package com.kartaguez.pocoma.infra.postgres.core.history;

import com.kartaguez.pocoma.domain.pot.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Instant;
import java.util.*;

import javax.sql.DataSource;

/** SQL mechanics shared by the two concrete adapters; no domain mutation protocol. */
public final class PotHistoryRows {
    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final NamedParameterJdbcTemplate named;

    public PotHistoryRows(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
    }

    private record VersionWindow(long birth, long version, long latest) {}

    private record OpenRevisionState(
            long birth,
            long expected,
            long local,
            long from,
            Long first,
            Long firstTo,
            Long previous,
            Long previousTo) {
        static OpenRevisionState from(Map<String, Object> row, long birth, long expected) {
            return new OpenRevisionState(
                    birth,
                    expected,
                    number(row, "local_version"),
                    number(row, "active_from"),
                    (Long) row.get("first_from"),
                    (Long) row.get("first_to"),
                    (Long) row.get("previous_from"),
                    (Long) row.get("previous_to"));
        }

        boolean isValid(Map<String, Object> row) {
            if (!hasValidBounds(row)) return false;
            return local == 1 ? isValidInitial() : isValidContinuation();
        }

        private boolean hasValidBounds(Map<String, Object> row) {
            if (birth > expected || !isWithin(from, birth, expected)) return false;
            if (local < 1 || !Objects.equals(first, birth)) return false;
            if (number(row, "last_local") != local) return false;
            return !Boolean.TRUE.equals(row.get("overlapping"));
        }

        private boolean isValidInitial() {
            return from == birth && firstTo == null;
        }

        private boolean isValidContinuation() {
            if (!isStrictWithin(firstTo, birth, from)) return false;
            if (!isStrictLowerWithin(previous, birth, from)) return false;
            return Objects.equals(previousTo, from);
        }

        private static boolean isStrictWithin(Long value, long minExclusive, long maxInclusive) {
            return value != null && value > minExclusive && value <= maxInclusive;
        }

        private static boolean isStrictLowerWithin(Long value, long minInclusive, long maxExclusive) {
            return value != null && value >= minInclusive && value < maxExclusive;
        }
    }

    private static boolean isWithin(long value, long minInclusive, long maxInclusive) {
        return value >= minInclusive && value <= maxInclusive;
    }

    public void requireTransaction(boolean snapshot) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw bad("Caller-owned transaction on the WRITE data source required");
        }
        if (!snapshot && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw bad("WRITE transaction required");
        }
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            verifyConnectionSnapshot(connection, snapshot);
                            return null;
                        });
    }

    private static void verifyConnectionSnapshot(Connection connection, boolean snapshot)
            throws java.sql.SQLException {
        if (connection.getAutoCommit()) {
            throw bad("Coherent caller snapshot required");
        }
        if (snapshot
                && connection.getTransactionIsolation() < Connection.TRANSACTION_REPEATABLE_READ) {
            throw bad("Coherent caller snapshot required");
        }
    }

    public List<Map<String, Object>> versions(PotId pot) {
        var rows =
                jdbc.queryForList(
                        "select version,recorded_at from v3_pot_version where pot_id=? order by"
                                + " version",
                        pot.value());
        long expected = 1;
        for (var row : rows) {
            if (number(row, "version") != expected) throw bad("Pot version history gap");
            expected = Math.incrementExact(expected);
        }
        if (rows.isEmpty()
                && Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "select exists(select 1 from v3_pot_identity where pot_id=?)",
                                Boolean.class,
                                pot.value()))) throw bad("Pot identity without version history");
        return rows;
    }

    public Map<UUID, Long> identities(String kind, PotId pot, Set<UUID> selected) {
        return identities(kind, pot, selected, null);
    }

    public Map<UUID, Long> identities(String kind, PotId pot, Set<UUID> selected, Long version) {
        if (selected != null && selected.isEmpty()) return Map.of();
        // Keep identity selection even for exhaustive callers: orphan/future identities must
        // retain their existing exclusion and corruption diagnostics. One typed JDBC array
        // preserves that predicate without one bind parameter per historical identity.
        String condition = selected == null ? "" : " and object_id = any(:ids)";
        var params = new HashMap<String, Object>();
        params.put("pot", pot.value());
        if (selected != null) params.put("ids", new SqlArrayValue("uuid", selected.toArray()));
        if (version != null) {
            params.put("version", version);
            condition += " and created_at<=:version";
        }
        var result = new LinkedHashMap<UUID, Long>();
        for (var row :
                named.queryForList(
                        "select object_id,created_at from v3_"
                                + kind
                                + "_identity where pot_id=:pot"
                                + condition
                                + " order by object_id",
                        params)) result.put(uuid(row, "object_id"), number(row, "created_at"));
        return Map.copyOf(result);
    }

    /** One indexed metadata lookup, never the global version chain. */
    public Map<String, Object> version(PotId pot, Long requested) {
        var latest =
                jdbc.queryForList(
                        "select version,recorded_at from v3_pot_version where pot_id=? order by"
                                + " version desc limit 1",
                        pot.value());
        if (latest.isEmpty()) {
            if (Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "select exists(select 1 from v3_pot_identity where pot_id=?)",
                            Boolean.class,
                            pot.value()))) throw bad("Pot identity without version history");
            return null;
        }
        var last = latest.getFirst();
        if (requested == null) return last;
        var exact =
                jdbc.queryForList(
                        "select version,recorded_at from v3_pot_version where pot_id=? and"
                                + " version=?",
                        pot.value(),
                        requested);
        if (exact.size() != 1) throw bad("Requested Pot version does not exist");
        var result = new HashMap<>(exact.getFirst());
        result.put("latest", number(last, "version"));
        return result;
    }

    /**
     * Only applicable rows cross JDBC. Global chain validation is an explicit separate operation.
     */
    public Map<UUID, Map<String, Object>> component(
            String table, PotId pot, long version, long latest, Map<UUID, Long> births) {
        return component(table, pot, version, latest, births, "*");
    }

    private record ApplicableQuery(
            String table,
            PotId pot,
            long version,
            long latest,
            Map<UUID, Long> births,
            String projection) {}

    public Map<UUID, Map<String, Object>> component(
            String table,
            PotId pot,
            long version,
            long latest,
            Map<UUID, Long> births,
            String projection) {
        if (births.isEmpty()) return Map.of();
        var query = new ApplicableQuery(table, pot, version, latest, births, projection);
        var applicable = queryApplicableRows(query);
        var result = indexApplicableRows(query, applicable);
        verifyBirthCoverage(table, version, births, result);
        return Map.copyOf(result);
    }

    private static Map<UUID, Map<String, Object>> indexApplicableRows(
            ApplicableQuery query, List<Map<String, Object>> applicable) {
        var result = new LinkedHashMap<UUID, Map<String, Object>>();
        for (var row : applicable) {
            UUID id = uuid(row, "object_id");
            var window = new VersionWindow(query.births().get(id), query.version(), query.latest());
            if (!isApplicableRowValid(row, window)) {
                throw bad("Invalid applicable revision in " + query.table());
            }
            if (result.put(id, row) != null) {
                throw bad("Multiple applicable revisions in " + query.table());
            }
        }
        return result;
    }

    private List<Map<String, Object>> queryApplicableRows(ApplicableQuery query) {
        String columns = projectionColumns(query.projection());
        String interval = applicableIntervalSql(query.version(), query.latest());
        return named.queryForList(
                "select "
                        + columns
                        + " from v3_"
                        + query.table()
                        + " where pot_id=:pot and object_id = any(:ids) and "
                        + interval,
                Map.of(
                        "pot",
                        query.pot().value(),
                        "ids",
                        new SqlArrayValue("uuid", query.births().keySet().toArray()),
                        "version",
                        query.version()));
    }

    private static String projectionColumns(String projection) {
        if (projection.equals("*")) return "*";
        return "revision_id,pot_id,object_id,local_version,active_from,active_to," + projection;
    }

    private static String applicableIntervalSql(long version, long latest) {
        if (version == latest) return "active_to is null";
        return "active_from<=:version and (active_to is null or :version<active_to) and"
                + " int8range(active_from,active_to,'[)') @> cast(:version as bigint)";
    }

    private static boolean isApplicableRowValid(Map<String, Object> row, VersionWindow window) {
        long from = number(row, "active_from");
        if (!isWithin(from, window.birth(), window.version()) || from > window.latest()) {
            return false;
        }
        Long to = (Long) row.get("active_to");
        return to == null || (to > window.version() && to <= window.latest());
    }

    private static void verifyBirthCoverage(
            String table,
            long version,
            Map<UUID, Long> births,
            Map<UUID, Map<String, Object>> result) {
        for (var birth : births.entrySet()) {
            boolean present = result.containsKey(birth.getKey());
            if (birth.getValue() <= version && !present) {
                throw bad("Missing applicable " + table);
            }
            if (birth.getValue() > version && present) {
                throw bad("Revision before identity birth in " + table);
            }
        }
    }

    /**
     * WRITE preconditions for the open revisions only. First, immediate predecessor and last local
     * keys are indexed probes, not a reconstruction of the intervening history.
     */
    public Map<UUID, Map<String, Object>> openComponent(
            String table, PotId pot, long expected, Map<UUID, Long> births) {
        if (births.isEmpty()) return Map.of();
        var current = queryOpenRows(table, pot, births);
        var result = indexOpenRows(table, current, expected, births);
        if (!result.keySet().equals(births.keySet())) {
            throw bad("Missing open revision in " + table);
        }
        return Map.copyOf(result);
    }

    private static Map<UUID, Map<String, Object>> indexOpenRows(
            String table,
            List<Map<String, Object>> current,
            long expected,
            Map<UUID, Long> births) {
        var result = new LinkedHashMap<UUID, Map<String, Object>>();
        for (var row : current) {
            UUID id = uuid(row, "object_id");
            var state = OpenRevisionState.from(row, births.get(id), expected);
            if (!state.isValid(row)) {
                throw bad("Invalid open revision or adjacent history in " + table);
            }
            if (result.put(id, row) != null) throw bad("Multiple open revisions in " + table);
        }
        return result;
    }

    private List<Map<String, Object>> queryOpenRows(
            String table, PotId pot, Map<UUID, Long> births) {
        String relation = "v3_" + table;
        return named.queryForList(
                "select c.*,f.active_from as first_from,f.active_to as first_to,"
                        + " p.active_from as previous_from,p.active_to as previous_to,"
                        + " l.local_version as last_local,"
                        + " exists(select 1 from "
                        + relation
                        + " o where o.pot_id=c.pot_id"
                        + " and o.object_id=c.object_id and o.revision_id<>c.revision_id"
                        + " and (o.active_to is null or (o.active_to>o.active_from and o.active_to>c.active_from)))"
                        + " as overlapping"
                        + " from "
                        + relation
                        + " c left join lateral(select active_from,active_to from "
                        + relation
                        + " where pot_id=c.pot_id and object_id=c.object_id and"
                        + " local_version=1 limit 1) f on true"
                        + " left join lateral(select active_from,active_to from "
                        + relation
                        + " where pot_id=c.pot_id and object_id=c.object_id and"
                        + " local_version=c.local_version-1 limit 1) p on true"
                        + " left join lateral(select local_version from "
                        + relation
                        + " where pot_id=c.pot_id and object_id=c.object_id order by"
                        + " local_version desc limit 1) l on true where c.pot_id=:pot and"
                        + " c.object_id = any(:ids) and c.active_to is null",
                Map.of(
                        "pot",
                        pot.value(),
                        "ids",
                        new SqlArrayValue("uuid", births.keySet().toArray())));
    }

    /**
     * Validate complete interval chains, then select directly at V. Empty is allowed only before
     * birth.
     */
    public Map<UUID, Map<String, Object>> checkedComponent(
            String table, PotId pot, long version, long latest, Map<UUID, Long> births) {
        return checkedComponent(table, pot, version, latest, births, "*");
    }

    public Map<UUID, Map<String, Object>> checkedComponent(
            String table,
            PotId pot,
            long version,
            long latest,
            Map<UUID, Long> births,
            String projection) {
        if (births.isEmpty()) return Map.of();
        var groups = groupRowsByObjectId(queryAllRows(table, pot, births, projection));
        var result = new LinkedHashMap<UUID, Map<String, Object>>();
        for (var birth : births.entrySet()) {
            var chain = groups.getOrDefault(birth.getKey(), List.of());
            var window = new VersionWindow(birth.getValue(), version, latest);
            var applicable = selectApplicableFromChain(table, chain, window);
            if (applicable != null) result.put(birth.getKey(), applicable);
        }
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> queryAllRows(
            String table, PotId pot, Map<UUID, Long> births, String projection) {
        return named.queryForList(
                "select "
                        + projectionColumns(projection)
                        + " from v3_"
                        + table
                        + " where pot_id=:pot and object_id = any(:ids) order by"
                        + " object_id,local_version",
                Map.of(
                        "pot",
                        pot.value(),
                        "ids",
                        new SqlArrayValue("uuid", births.keySet().toArray())));
    }

    private static Map<UUID, List<Map<String, Object>>> groupRowsByObjectId(
            List<Map<String, Object>> rows) {
        var groups = new HashMap<UUID, List<Map<String, Object>>>();
        for (var row : rows) {
            groups.computeIfAbsent(uuid(row, "object_id"), key -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private static Map<String, Object> selectApplicableFromChain(
            String table, List<Map<String, Object>> chain, VersionWindow window) {
        if (chain.isEmpty()) throw bad("Missing " + table + " history for known identity");
        validateCompleteChain(table, chain, window);
        var applicable = findCoveringRevision(chain, window.version());
        if (window.birth() <= window.version() && applicable == null) {
            throw bad("Missing applicable " + table);
        }
        return applicable;
    }

    private static void validateCompleteChain(
            String table, List<Map<String, Object>> chain, VersionWindow window) {
        long local = 1;
        long from = window.birth();
        for (var row : chain) {
            if (!isChainLinkValid(row, local, from, window.latest())) {
                throw bad("Gap, overlap or invalid revision in " + table);
            }
            Long to = (Long) row.get("active_to");
            local = Math.incrementExact(local);
            from = to == null ? -1 : to;
        }
        if (from != -1) throw bad("History has no open revision in " + table);
    }

    private static Map<String, Object> findCoveringRevision(
            List<Map<String, Object>> chain, long version) {
        Map<String, Object> applicable = null;
        for (var row : chain) {
            long actualFrom = number(row, "active_from");
            Long to = (Long) row.get("active_to");
            if (!coversVersion(actualFrom, to, version)) continue;
            if (applicable != null) throw bad("Multiple applicable revisions");
            applicable = row;
        }
        return applicable;
    }

    private static boolean isChainLinkValid(
            Map<String, Object> row, long expectedLocal, long expectedFrom, long latest) {
        long actualFrom = number(row, "active_from");
        if (number(row, "local_version") != expectedLocal || actualFrom != expectedFrom) {
            return false;
        }
        if (actualFrom > latest) return false;
        Long to = (Long) row.get("active_to");
        return to == null || (to > actualFrom && to <= latest);
    }

    private static boolean coversVersion(long actualFrom, Long to, long version) {
        if (actualFrom > version) return false;
        return to == null || version < to;
    }

    public static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    public static UUID uuid(Map<String, Object> row, String column) {
        return (UUID) row.get(column);
    }

    public static Instant instant(Map<String, Object> row, String column) {
        return ((java.sql.Timestamp) row.get(column)).toInstant();
    }

    public static InconsistentPotFactsException bad(String message) {
        return new InconsistentPotFactsException(message);
    }
}
