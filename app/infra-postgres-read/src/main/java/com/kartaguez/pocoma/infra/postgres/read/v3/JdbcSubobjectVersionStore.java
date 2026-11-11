package com.kartaguez.pocoma.infra.postgres.read.v3;

import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

import javax.sql.DataSource;

/** No autonomous connections, transaction boundaries, updates or slot operations. */
public final class JdbcSubobjectVersionStore implements SubobjectVersionStore {
    private static final int CHUNK = 256;
    private final DataSource source;
    private final JdbcTemplate sql;

    public JdbcSubobjectVersionStore(DataSource source, SpringAttemptTransactions owner) {
        this.source = Objects.requireNonNull(source);
        Objects.requireNonNull(owner).requireSource(source);
        this.sql = new JdbcTemplate(source);
    }

    @Override public Map<SubobjectKey, SubobjectVersion> readAll(Collection<SubobjectKey> keys, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(
                source,
                context,
                () -> {
                    return Map.copyOf(load(sortedKeys(keys)));
                });
    }

    @Override public void insertOrVerifyAll(Collection<SubobjectVersion> candidates) {
        SpringAttemptTransactions.guarded(
                source,
                () -> {
                    requireReadCommitted();
                    var sorted = sortedVersions(candidates);
                    for (int start = 0; start < sorted.size(); start += CHUNK) {
                        var chunk = sorted.subList(start, Math.min(start + CHUNK, sorted.size()));
                        var args = new ArrayList<Object>();
                        var tuples = new ArrayList<String>();
                        for (var candidate : chunk) {
                            tuples.add("(?,?,?,?,1,?::jsonb)");
                            keyArguments(args, candidate.key());
                            args.add(SubobjectJson.encode(candidate));
                        }
                        sql.update(
                                "insert into"
                                    + " read_subobject_version(pot_id,kind,object_id,local_version,schema_version,payload)"
                                    + " values "
                                        + String.join(",", tuples)
                                        + " on conflict(pot_id,kind,object_id,local_version) do"
                                        + " nothing",
                                args.toArray());
                        // New READ_COMMITTED statement snapshot, after any unique-index wait.
                        // DO NOT merge with INSERT in a CTE: it can miss a concurrent winner.
                        compare(chunk, load(chunk.stream().map(SubobjectVersion::key).toList()));
                    }
                    return null;
                });
    }

    @Override public void requireStored(Collection<SubobjectVersion> expected) {
        SpringAttemptTransactions.guarded(source, () -> {
            requireReadCommitted();
            var sorted = sortedVersions(expected);
            compare(sorted, load(sorted.stream().map(SubobjectVersion::key).toList()));
            return null;
        });
    }

    @Override public ReadEvidence evidence(Collection<SubobjectVersion> expected, RecoveryReadContext context) {
        return SpringAttemptTransactions.observed(source, context, () -> {
            var sorted=sortedVersions(expected);
            final Map<SubobjectKey,SubobjectVersion> actual;
            try { actual=load(sorted.stream().map(SubobjectVersion::key).toList()); }
            catch(CorruptSubobjectException corruption) { return ReadEvidence.DIVERGENT; }
            for(var candidate:sorted) if(actual.containsKey(candidate.key()) && !candidate.equals(actual.get(candidate.key()))) return ReadEvidence.DIVERGENT;
            return actual.size()==sorted.size()?ReadEvidence.SATISFIED:ReadEvidence.MISSING;
        });
    }

    private static void requireReadCommitted() {
        if (!Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                TransactionDefinition.ISOLATION_READ_COMMITTED))
            throw new IllegalTransactionStateException("Subobject writer requires READ_COMMITTED");
    }

    private static void compare(List<SubobjectVersion> expected, Map<SubobjectKey, SubobjectVersion> stored) {
        for (var candidate : expected) {
            var actual = stored.get(candidate.key());
            if (actual == null) throw new CorruptSubobjectException(candidate.key());
            if (!actual.equals(candidate)) throw new SubobjectConflictException(candidate.key());
        }
    }

    private Map<SubobjectKey, SubobjectVersion> load(List<SubobjectKey> sorted) {
        var result = new LinkedHashMap<SubobjectKey, SubobjectVersion>();
        for (int start = 0; start < sorted.size(); start += CHUNK) {
            var chunk = sorted.subList(start, Math.min(start + CHUNK, sorted.size()));
            var args = new ArrayList<Object>();
            var tuples = new ArrayList<String>();
            for (var key : chunk) {
                tuples.add("(?,?,?,?)");
                keyArguments(args, key);
            }
            sql.query(
                    "select pot_id,kind,object_id,local_version,schema_version,payload::text from"
                        + " read_subobject_version where (pot_id,kind,object_id,local_version) in ("
                            + String.join(",", tuples)
                            + ")",
                    rs -> {
                        SubobjectKey key;
                        try {
                            key =
                                    new SubobjectKey(
                                            rs.getObject(1, UUID.class),
                                            SubobjectKind.valueOf(rs.getString(2)),
                                            rs.getObject(3, UUID.class),
                                            rs.getLong(4));
                        } catch (IllegalArgumentException | NullPointerException invalid) {
                            throw new CorruptSubobjectException(null);
                        }
                        result.put(key, SubobjectJson.decode(key, rs.getInt(5), rs.getString(6)));
                    },
                    args.toArray());
        }
        return result;
    }

    private static void keyArguments(List<Object> args, SubobjectKey key) {
        args.add(key.potId()); args.add(key.kind().name()); args.add(key.objectId()); args.add(key.localVersion());
    }

    private static List<SubobjectKey> sortedKeys(Collection<SubobjectKey> keys) {
        Objects.requireNonNull(keys);
        var result = new ArrayList<>(keys);
        result.forEach(Objects::requireNonNull);
        result.sort(Comparator.naturalOrder());
        for (int i = 1; i < result.size(); i++)
            if (result.get(i - 1).equals(result.get(i)))
                throw new IllegalArgumentException("Duplicate subobject key");
        return result;
    }

    private static List<SubobjectVersion> sortedVersions(Collection<SubobjectVersion> versions) {
        Objects.requireNonNull(versions);
        var result = new ArrayList<>(versions);
        result.forEach(Objects::requireNonNull);
        result.sort(Comparator.comparing(SubobjectVersion::key));
        for (int i = 1; i < result.size(); i++)
            if (result.get(i - 1).key().equals(result.get(i).key()))
                throw new IllegalArgumentException("Duplicate subobject key");
        return result;
    }
}
