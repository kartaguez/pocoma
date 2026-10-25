package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import com.kartaguez.pocoma.domain.pot.v3.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
        if (selected != null && selected.isEmpty()) return Map.of();
        String condition = selected == null ? "" : " and object_id in (:ids)";
        var params = new HashMap<String, Object>();
        params.put("pot", pot.value());
        if (selected != null) params.put("ids", selected);
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

    /**
     * Validate complete interval chains, then select directly at V. Empty is allowed only before
     * birth.
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
        var all =
                named.queryForList(
                        "select "
                                + columns
                                + " from v3_"
                                + table
                                + " where pot_id=:pot and object_id in (:ids) order by"
                                + " object_id,local_version",
                        Map.of("pot", pot.value(), "ids", births.keySet()));
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
