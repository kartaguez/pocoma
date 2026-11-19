package com.kartaguez.pocoma.infra.postgres.read;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.port.projection.PotBalanceStore;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import javax.sql.DataSource;
import java.util.*;
import java.math.BigInteger;
public final class JdbcPotBalanceStore implements PotBalanceStore {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ReadJdbc db;
    public JdbcPotBalanceStore(DataSource source,SpringAttemptTransactions owner) { db=new ReadJdbc(source,owner); }
    private String encode(PotBalance b) {
        var a=JSON.createArrayNode(); b.values().forEach((id,f) -> a.addObject().put("id",id.toString()).put("n",f.numerator().toString()).put("d",f.denominator().toString())); return a.toString();
    }
    private PotBalance decode(UUID p, long v, String payload) {
        try {
            var array = JSON.readTree(payload);
            if (!array.isArray()) throw new IllegalArgumentException();
            var values = new LinkedHashMap<UUID, Fraction>();
            for (var n : array) {
                parseBalanceEntry(n, values);
            }
            var b = new PotBalance(p, v, values);
            if (!array.equals(JSON.readTree(encode(b)))) throw new IllegalArgumentException();
            return b;
        } catch (java.io.IOException | RuntimeException invalid) {
            throw new IllegalStateException("Corrupt PotBalance");
        }
    }

    private static void parseBalanceEntry(JsonNode n, Map<UUID, Fraction> values) {
        if (!n.isObject() || n.size() != 3) throw new IllegalArgumentException();
        for (String field : List.of("id", "n", "d")) {
            if (!n.path(field).isTextual()) throw new IllegalArgumentException();
        }
        var id = UUID.fromString(n.get("id").textValue());
        var fraction =
                new Fraction(
                        new BigInteger(n.get("n").textValue()),
                        new BigInteger(n.get("d").textValue()));
        if (values.put(id, fraction) != null) throw new IllegalArgumentException();
    }
    private Optional<PotBalance> load(UUID p,long v) { return db.sql.query("select payload::text from read_pot_balance where pot_id=? and pot_version=?",(r,n) -> decode(p,v,r.getString(1)),p,v).stream().findFirst(); }
    public Optional<PotBalance> read(UUID p,long v,RecoveryReadContext c) { return db.read(c,() -> load(p,v)); }
    public ReadEvidence evidence(UUID p,long v,Optional<PotBalance> expected,RecoveryReadContext c) { return db.read(c,() -> {
        try {
            var actual=load(p,v);
            if(expected.equals(actual)) return ReadEvidence.SATISFIED;
            return actual.isEmpty()?ReadEvidence.MISSING:ReadEvidence.DIVERGENT;
        } catch(IllegalStateException positiveCorruption) { return ReadEvidence.DIVERGENT; }
    }); }
    public void insertOrVerify(PotBalance b) { db.write(() -> {
        db.sql.update("insert into read_pot_balance(pot_id,pot_version,schema_version,payload) values (?,?,1,?::jsonb) on conflict do nothing",b.potId(),b.version(),encode(b)); ReadJdbc.same(b,load(b.potId(),b.version()).orElse(null)); return null;
    }); }
    public void requireStored(UUID p,long v,Optional<PotBalance> expected) { db.write(() -> { ReadJdbc.same(expected,load(p,v)); return null; }); }

    private Optional<PotBalanceAggregate> loadAggregate(UUID pot, long version) {
        return db.sql
                .query(
                        "select schema_version,bucket_count,payload::text from"
                            + " read_pot_balance_aggregate where pot_id=? and pot_version=?",
                        (r, n) -> {
                            int schemaVersion = r.getInt(1);
                            int bucketCount = r.getInt(2);
                            var agg =
                                    PotBalanceAggregateJson.decode(
                                            pot, version, schemaVersion, r.getString(3));
                            if (agg.bucketCount() != bucketCount) {
                                throw new IllegalStateException("Corrupt PotBalanceAggregate");
                            }
                            return agg;
                        },
                        pot,
                        version)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<PotBalanceAggregate> readLatestAggregateBefore(
            UUID pot,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(pot);
                    if (beforeVersionExclusive <= 1
                            || minVersionInclusive >= beforeVersionExclusive) {
                        return Optional.empty();
                    }
                    long minBound = Math.max(1L, minVersionInclusive);
                    return db.sql
                            .query(
                                    "select pot_version,schema_version,bucket_count,payload::text"
                                        + " from read_pot_balance_aggregate where pot_id=? and"
                                        + " pot_version<? and pot_version>=? order by pot_version"
                                        + " desc limit 1",
                                    (r, n) -> {
                                        long version = r.getLong(1);
                                        int schemaVersion = r.getInt(2);
                                        int bucketCount = r.getInt(3);
                                        var agg =
                                                PotBalanceAggregateJson.decode(
                                                        pot,
                                                        version,
                                                        schemaVersion,
                                                        r.getString(4));
                                        if (agg.bucketCount() != bucketCount) {
                                            throw new IllegalStateException(
                                                    "Corrupt PotBalanceAggregate");
                                        }
                                        return agg;
                                    },
                                    pot,
                                    beforeVersionExclusive,
                                    minBound)
                            .stream()
                            .findFirst();
                });
    }

    @Override
    public void insertOrVerifyAggregate(PotBalanceAggregate aggregate) {
        db.write(
                () -> {
                    db.sql.update(
                            "insert into"
                                + " read_pot_balance_aggregate(pot_id,pot_version,schema_version,bucket_count,payload)"
                                + " values (?,?,?,?,?::jsonb) on conflict do nothing",
                            aggregate.potId(),
                            aggregate.version(),
                            PotBalanceAggregateJson.SCHEMA_VERSION,
                            aggregate.bucketCount(),
                            PotBalanceAggregateJson.encode(aggregate));
                    ReadJdbc.same(
                            aggregate,
                            loadAggregate(aggregate.potId(), aggregate.version()).orElse(null));
                    return null;
                });
    }

    @Override
    public void requireAggregateStored(PotBalanceAggregate expected) {
        db.write(
                () -> {
                    ReadJdbc.same(
                            expected,
                            loadAggregate(expected.potId(), expected.version()).orElse(null));
                    return null;
                });
    }

    public void closePublication() { SpringAttemptTransactions.closePublication(db.source); }
}
