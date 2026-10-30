package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.domain.pot.v3.InconsistentPotFactsException;
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
final class PotHistoryRows {
    final DataSource dataSource;
    final JdbcTemplate jdbc;
    final NamedParameterJdbcTemplate named;

    PotHistoryRows(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
    }

    void requireTransaction(boolean snapshot) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource))
            throw bad("Caller-owned transaction on the WRITE data source required");
        if (!snapshot && TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw bad("WRITE transaction required");
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            if (connection.getAutoCommit()
                                    || (snapshot
                                            && connection.getTransactionIsolation()
                                                    < Connection.TRANSACTION_REPEATABLE_READ))
                                throw bad("Coherent caller snapshot required");
                            return null;
                        });
    }

    List<Map<String, Object>> versions(PotId pot) {
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

    Map<UUID, Long> identities(String kind, PotId pot, Set<UUID> selected) {
        return identities(kind, pot, selected, null);
    }

    Map<UUID, Long> identities(String kind, PotId pot, Set<UUID> selected, Long version) {
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
    Map<String, Object> version(PotId pot, Long requested) {
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
    Map<UUID, Map<String, Object>> component(
            String table, PotId pot, long version, long latest, Map<UUID, Long> births) {
        return component(table, pot, version, latest, births, "*");
    }

    Map<UUID, Map<String, Object>> component(
            String table,
            PotId pot,
            long version,
            long latest,
            Map<UUID, Long> births,
            String projection) {
        if (births.isEmpty()) return Map.of();
        String columns =
                projection.equals("*")
                        ? "*"
                        : "revision_id,pot_id,object_id,local_version,active_from,active_to,"
                                + projection;
        String interval =
                version == latest
                        ? "active_to is null"
                        : "active_from<=:version and (active_to is null or :version<active_to) and"
                                + " int8range(active_from,active_to,'[)') @> cast(:version as"
                                + " bigint)";
        var applicable =
                named.queryForList(
                        "select "
                                + columns
                                + " from v3_"
                                + table
                                + " where pot_id=:pot and object_id = any(:ids) and "
                                + interval,
                        Map.of(
                                "pot",
                                pot.value(),
                                "ids",
                                new SqlArrayValue("uuid", births.keySet().toArray()),
                                "version",
                                version));
        var result = new LinkedHashMap<UUID, Map<String, Object>>();
        for (var row : applicable) {
            UUID id = uuid(row, "object_id");
            long from = number(row, "active_from");
            Long to = (Long) row.get("active_to");
            if (from < births.get(id)
                    || from > version
                    || from > latest
                    || (to != null && (to <= version || to > latest)))
                throw bad("Invalid applicable revision in " + table);
            if (result.put(id, row) != null) throw bad("Multiple applicable revisions in " + table);
        }
        for (var birth : births.entrySet()) {
            if (birth.getValue() <= version && !result.containsKey(birth.getKey()))
                throw bad("Missing applicable " + table);
            if (birth.getValue() > version && result.containsKey(birth.getKey()))
                throw bad("Revision before identity birth in " + table);
        }
        return Map.copyOf(result);
    }

    /**
     * WRITE preconditions for the open revisions only. First, immediate predecessor and last local
     * keys are indexed probes, not a reconstruction of the intervening history.
     */
    Map<UUID, Map<String, Object>> openComponent(
            String table, PotId pot, long expected, Map<UUID, Long> births) {
        if (births.isEmpty()) return Map.of();
        String relation = "v3_" + table;
        var current =
                named.queryForList(
                        "select c.*,f.active_from as first_from,f.active_to as first_to,"
                                + " p.active_from as previous_from,p.active_to as previous_to,"
                                + " l.local_version as last_local,"
                                + " exists(select 1 from "
                                + relation
                                + " o where o.pot_id=c.pot_id"
                                + " and o.object_id=c.object_id and o.revision_id<>c.revision_id"
                                + " and int8range(o.active_from,o.active_to,'[)')"
                                + " && int8range(c.active_from,null,'[)')) as overlapping"
                                + " from "
                                + relation
                                + " c left join "
                                + relation
                                + " f on f.pot_id=c.pot_id and f.object_id=c.object_id and"
                                + " f.local_version=1 left join "
                                + relation
                                + " p on p.pot_id=c.pot_id and p.object_id=c.object_id and"
                                + " p.local_version=c.local_version-1 left join lateral(select"
                                + " local_version from "
                                + relation
                                + " where pot_id=c.pot_id and object_id=c.object_id order by"
                                + " local_version desc limit 1) l on true where c.pot_id=:pot and"
                                + " c.object_id = any(:ids) and c.active_to is null",
                        Map.of(
                                "pot",
                                pot.value(),
                                "ids",
                                new SqlArrayValue("uuid", births.keySet().toArray())));
        var result = new LinkedHashMap<UUID, Map<String, Object>>();
        for (var row : current) {
            UUID id = uuid(row, "object_id");
            long birth = births.get(id), local = number(row, "local_version");
            long from = number(row, "active_from");
            Long first = (Long) row.get("first_from"), firstTo = (Long) row.get("first_to");
            Long previous = (Long) row.get("previous_from"),
                    previousTo = (Long) row.get("previous_to");
            if (birth > expected
                    || from < birth
                    || from > expected
                    || local < 1
                    || first == null
                    || first != birth
                    || number(row, "last_local") != local
                    || Boolean.TRUE.equals(row.get("overlapping"))
                    || (local == 1 && (from != birth || firstTo != null))
                    || (local > 1
                            && (firstTo == null
                                    || firstTo <= birth
                                    || firstTo > from
                                    || previous == null
                                    || previous < birth
                                    || previous >= from
                                    || previousTo == null
                                    || previousTo != from)))
                throw bad("Invalid open revision or adjacent history in " + table);
            if (result.put(id, row) != null) throw bad("Multiple open revisions in " + table);
        }
        if (!result.keySet().equals(births.keySet()))
            throw bad("Missing open revision in " + table);
        return Map.copyOf(result);
    }

    /**
     * Validate complete interval chains, then select directly at V. Empty is allowed only before
     * birth.
     */
    Map<UUID, Map<String, Object>> checkedComponent(
            String table, PotId pot, long version, long latest, Map<UUID, Long> births) {
        return checkedComponent(table, pot, version, latest, births, "*");
    }

    Map<UUID, Map<String, Object>> checkedComponent(
            String table,
            PotId pot,
            long version,
            long latest,
            Map<UUID, Long> births,
            String projection) {
        if (births.isEmpty()) return Map.of();
        String columns =
                projection.equals("*")
                        ? "*"
                        : "revision_id,pot_id,object_id,local_version,active_from,active_to,"
                                + projection;
        var all =
                named.queryForList(
                        "select "
                                + columns
                                + " from v3_"
                                + table
                                + " where pot_id=:pot and object_id = any(:ids) order by"
                                + " object_id,local_version",
                        Map.of(
                                "pot",
                                pot.value(),
                                "ids",
                                new SqlArrayValue("uuid", births.keySet().toArray())));
        var groups = new HashMap<UUID, List<Map<String, Object>>>();
        for (var row : all)
            groups.computeIfAbsent(uuid(row, "object_id"), key -> new ArrayList<>()).add(row);
        var result = new LinkedHashMap<UUID, Map<String, Object>>();
        for (var birth : births.entrySet()) {
            var chain = groups.getOrDefault(birth.getKey(), List.of());
            long local = 1, from = birth.getValue();
            Map<String, Object> applicable = null;
            if (chain.isEmpty()) throw bad("Missing " + table + " history for known identity");
            for (var row : chain) {
                long actualFrom = number(row, "active_from");
                Long to = (Long) row.get("active_to");
                if (number(row, "local_version") != local
                        || actualFrom != from
                        || actualFrom > latest
                        || (to != null && (to <= actualFrom || to > latest)))
                    throw bad("Gap, overlap or invalid revision in " + table);
                if (actualFrom <= version && (to == null || version < to)) {
                    if (applicable != null) throw bad("Multiple applicable revisions");
                    applicable = row;
                }
                local = Math.incrementExact(local);
                from = to == null ? -1 : to;
            }
            if (from != -1) throw bad("History has no open revision in " + table);
            if (birth.getValue() <= version && applicable == null)
                throw bad("Missing applicable " + table);
            if (applicable != null) result.put(birth.getKey(), applicable);
        }
        return Map.copyOf(result);
    }

    static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    static UUID uuid(Map<String, Object> row, String column) {
        return (UUID) row.get(column);
    }

    static Instant instant(Map<String, Object> row, String column) {
        return ((java.sql.Timestamp) row.get(column)).toInstant();
    }

    static InconsistentPotFactsException bad(String message) {
        return new InconsistentPotFactsException(message);
    }
}
