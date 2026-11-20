package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateBinaryCodec;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateContent;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceContent;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.PotBalanceStore;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcPotBalanceStore implements PotBalanceStore {
    private final ReadJdbc db;

    public JdbcPotBalanceStore(DataSource source, SpringAttemptTransactions owner) {
        this.db = new ReadJdbc(source, owner);
    }

    private Optional<PotBalance> load(UUID potId, long version) {
        return db.sql
                .query(
                        """
                        select c.content_hash, c.schema_version, c.shareholders_count, c.payload
                        from read_pot_balance b
                        join read_pot_balance_content c on c.content_hash = b.content_hash
                        where b.pot_id = ? and b.pot_version = ?
                        """,
                        (rs, n) -> decodeBalance(
                                potId,
                                version,
                                rs.getBytes(1),
                                rs.getInt(2),
                                rs.getInt(3),
                                rs.getBytes(4)),
                        potId,
                        version)
                .stream()
                .findFirst();
    }

    private static PotBalance decodeBalance(
            UUID potId,
            long version,
            byte[] hashBytes,
            int schemaVersion,
            int shareholdersCount,
            byte[] payload) {
        try {
            return PotBalanceBinaryCodec.decode(
                    potId,
                    version,
                    schemaVersion,
                    shareholdersCount,
                    payload,
                    ContentHash.of(hashBytes));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Corrupt PotBalance", invalid);
        }
    }

    private Optional<PotBalanceAggregate> loadAggregate(UUID potId, long version) {
        return db.sql
                .query(
                        """
                        select c.content_hash, c.schema_version, c.shareholders_count, c.bucket_count, c.payload
                        from read_pot_balance_aggregate a
                        join read_pot_balance_aggregate_content c on c.content_hash = a.content_hash
                        where a.pot_id = ? and a.pot_version = ?
                        """,
                        (rs, n) -> decodeAggregate(
                                potId,
                                version,
                                rs.getBytes(1),
                                rs.getInt(2),
                                rs.getInt(3),
                                rs.getInt(4),
                                rs.getBytes(5)),
                        potId,
                        version)
                .stream()
                .findFirst();
    }

    private static PotBalanceAggregate decodeAggregate(
            UUID potId,
            long version,
            byte[] hashBytes,
            int schemaVersion,
            int shareholdersCount,
            int bucketCount,
            byte[] payload) {
        try {
            return PotBalanceAggregateBinaryCodec.decode(
                    potId,
                    version,
                    schemaVersion,
                    shareholdersCount,
                    bucketCount,
                    payload,
                    ContentHash.of(hashBytes));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Corrupt PotBalanceAggregate", invalid);
        }
    }

    @Override
    public Optional<PotBalance> read(UUID potId, long version, RecoveryReadContext context) {
        return db.read(context, () -> load(potId, version));
    }

    @Override
    public ReadEvidence evidence(
            UUID potId, long version, Optional<PotBalance> expected, RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    try {
                        var actual = load(potId, version);
                        if (expected.equals(actual)) {
                            return ReadEvidence.SATISFIED;
                        }
                        return actual.isEmpty() ? ReadEvidence.MISSING : ReadEvidence.DIVERGENT;
                    } catch (IllegalStateException positiveCorruption) {
                        return ReadEvidence.DIVERGENT;
                    }
                });
    }

    @Override
    public void insertOrVerify(PotBalance balance) {
        insertOrVerifyWithResult(balance);
    }

    @Override
    public WriteResult insertOrVerifyWithResult(PotBalance balance) {
        return db.write(
                () -> {
                    long encodeStart = System.nanoTime();
                    PotBalanceContent content = PotBalanceBinaryCodec.encode(balance);
                    Duration encodeElapsed = Duration.ofNanos(System.nanoTime() - encodeStart);
                    ContentWriteOutcome outcome = insertOrVerifyBalanceContent(content);
                    insertOrVerifyBalancePointer(balance.potId(), balance.version(), content.hash());
                    return new WriteResult(outcome, encodeElapsed);
                });
    }

    private ContentWriteOutcome insertOrVerifyBalanceContent(PotBalanceContent content) {
        byte[] hashBytes = content.hash().bytes();
        var inserted =
                db.sql.query(
                        """
                        insert into read_pot_balance_content(
                          content_hash, schema_version, shareholders_count, payload)
                        values (?, ?, ?, ?)
                        on conflict (content_hash) do nothing
                        returning content_hash
                        """,
                        (rs, n) -> rs.getBytes(1),
                        hashBytes,
                        content.schemaVersion(),
                        content.shareholdersCount(),
                        content.payload());
        if (!inserted.isEmpty()) {
            return ContentWriteOutcome.INSERTED;
        }
        var existing =
                db.sql.query(
                        """
                        select schema_version, shareholders_count, payload
                        from read_pot_balance_content
                        where content_hash = ?
                        """,
                        (rs, n) ->
                                content.exactContentEquals(
                                        rs.getInt(1), rs.getInt(2), rs.getBytes(3)),
                        hashBytes);
        if (existing.isEmpty() || !Boolean.TRUE.equals(existing.getFirst())) {
            throw new IllegalStateException(
                    "Divergent read_pot_balance_content for hash " + content.hash());
        }
        return ContentWriteOutcome.REUSED;
    }

    private void insertOrVerifyBalancePointer(UUID potId, long version, ContentHash expectedHash) {
        byte[] expectedBytes = expectedHash.bytes();
        var inserted =
                db.sql.query(
                        """
                        insert into read_pot_balance(pot_id, pot_version, content_hash)
                        values (?, ?, ?)
                        on conflict (pot_id, pot_version) do nothing
                        returning 1
                        """,
                        (rs, n) -> rs.getInt(1),
                        potId,
                        version,
                        expectedBytes);
        if (!inserted.isEmpty()) {
            return;
        }
        var actual =
                db.sql.query(
                        "select content_hash from read_pot_balance where pot_id = ? and pot_version = ?",
                        (rs, n) -> rs.getBytes(1),
                        potId,
                        version);
        if (actual.isEmpty() || !Arrays.equals(actual.getFirst(), expectedBytes)) {
            throw new IllegalStateException(
                    "Divergent read_pot_balance for pot=" + potId + " version=" + version);
        }
    }

    @Override
    public void requireStored(UUID potId, long version, Optional<PotBalance> expected) {
        db.write(
                () -> {
                    ReadJdbc.same(expected, load(potId, version));
                    return null;
                });
    }

    @Override
    public Optional<PotBalanceAggregate> readLatestAggregateBefore(
            UUID potId,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(potId);
                    if (beforeVersionExclusive <= 1
                            || minVersionInclusive >= beforeVersionExclusive) {
                        return Optional.empty();
                    }
                    long minBound = Math.max(1L, minVersionInclusive);
                    return db.sql
                            .query(
                                    """
                                    select a.pot_version, c.content_hash, c.schema_version,
                                           c.shareholders_count, c.bucket_count, c.payload
                                    from read_pot_balance_aggregate a
                                    join read_pot_balance_aggregate_content c on c.content_hash = a.content_hash
                                    where a.pot_id = ? and a.pot_version < ? and a.pot_version >= ?
                                    order by a.pot_version desc
                                    limit 1
                                    """,
                                    (rs, n) -> decodeAggregate(
                                            potId,
                                            rs.getLong(1),
                                            rs.getBytes(2),
                                            rs.getInt(3),
                                            rs.getInt(4),
                                            rs.getInt(5),
                                            rs.getBytes(6)),
                                    potId,
                                    beforeVersionExclusive,
                                    minBound)
                            .stream()
                            .findFirst();
                });
    }

    @Override
    public Optional<AggregateRef> readLatestAggregateRefBefore(
            UUID potId,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(potId);
                    if (beforeVersionExclusive <= 1
                            || minVersionInclusive >= beforeVersionExclusive) {
                        return Optional.empty();
                    }
                    long minBound = Math.max(1L, minVersionInclusive);
                    return db.sql
                            .query(
                                    """
                                    select a.pot_version, b.content_hash, a.content_hash
                                    from read_pot_balance_aggregate a
                                    join read_pot_balance b
                                      on b.pot_id = a.pot_id and b.pot_version = a.pot_version
                                    where a.pot_id = ? and a.pot_version < ? and a.pot_version >= ?
                                    order by a.pot_version desc
                                    limit 1
                                    """,
                                    (rs, n) -> new AggregateRef(
                                            rs.getLong(1),
                                            ContentHash.of(rs.getBytes(2)),
                                            ContentHash.of(rs.getBytes(3))),
                                    potId,
                                    beforeVersionExclusive,
                                    minBound)
                            .stream()
                            .findFirst();
                });
    }

    @Override
    public Optional<PotBalanceAggregate> readAggregateByHash(
            UUID potId, long version, ContentHash hash, RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(potId);
                    Objects.requireNonNull(hash);
                    byte[] hashBytes = hash.bytes();
                    return db.sql
                            .query(
                                    """
                                    select schema_version, shareholders_count, bucket_count, payload
                                    from read_pot_balance_aggregate_content
                                    where content_hash = ?
                                    """,
                                    (rs, n) -> decodeAggregate(
                                            potId,
                                            version,
                                            hashBytes,
                                            rs.getInt(1),
                                            rs.getInt(2),
                                            rs.getInt(3),
                                            rs.getBytes(4)),
                                    hashBytes)
                            .stream()
                            .findFirst();
                });
    }

    @Override
    public void linkExistingVersion(
            UUID potId, long version, ContentHash balanceHash, ContentHash aggregateHash) {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(balanceHash);
        Objects.requireNonNull(aggregateHash);
        db.write(
                () -> {
                    insertOrVerifyBalancePointer(potId, version, balanceHash);
                    insertOrVerifyAggregatePointer(potId, version, aggregateHash);
                    return null;
                });
    }

    @Override
    public void requireVersionHashesStored(
            UUID potId, long version, ContentHash balanceHash, ContentHash aggregateHash) {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(balanceHash);
        Objects.requireNonNull(aggregateHash);
        db.write(
                () -> {
                    var balActual =
                            db.sql.query(
                                    "select content_hash from read_pot_balance where pot_id = ? and pot_version = ?",
                                    (rs, n) -> rs.getBytes(1),
                                    potId,
                                    version);
                    if (balActual.isEmpty() || !Arrays.equals(balActual.getFirst(), balanceHash.bytes())) {
                        throw new IllegalStateException(
                                "Missing or divergent read_pot_balance hash for pot=" + potId + " version=" + version);
                    }
                    var aggActual =
                            db.sql.query(
                                    "select content_hash from read_pot_balance_aggregate where pot_id = ? and pot_version = ?",
                                    (rs, n) -> rs.getBytes(1),
                                    potId,
                                    version);
                    if (aggActual.isEmpty() || !Arrays.equals(aggActual.getFirst(), aggregateHash.bytes())) {
                        throw new IllegalStateException(
                                "Missing or divergent read_pot_balance_aggregate hash for pot=" + potId + " version=" + version);
                    }
                    return null;
                });
    }

    @Override
    public void insertOrVerifyAggregate(PotBalanceAggregate aggregate) {
        insertOrVerifyAggregateWithResult(aggregate);
    }

    @Override
    public WriteResult insertOrVerifyAggregateWithResult(PotBalanceAggregate aggregate) {
        return db.write(
                () -> {
                    long encodeStart = System.nanoTime();
                    PotBalanceAggregateContent content =
                            PotBalanceAggregateBinaryCodec.encode(aggregate);
                    Duration encodeElapsed = Duration.ofNanos(System.nanoTime() - encodeStart);
                    ContentWriteOutcome outcome = insertOrVerifyAggregateContent(content);
                    insertOrVerifyAggregatePointer(
                            aggregate.potId(), aggregate.version(), content.hash());
                    return new WriteResult(outcome, encodeElapsed);
                });
    }

    private ContentWriteOutcome insertOrVerifyAggregateContent(PotBalanceAggregateContent content) {
        byte[] hashBytes = content.hash().bytes();
        var inserted =
                db.sql.query(
                        """
                        insert into read_pot_balance_aggregate_content(
                          content_hash, schema_version, shareholders_count, bucket_count, payload)
                        values (?, ?, ?, ?, ?)
                        on conflict (content_hash) do nothing
                        returning content_hash
                        """,
                        (rs, n) -> rs.getBytes(1),
                        hashBytes,
                        content.schemaVersion(),
                        content.shareholdersCount(),
                        content.bucketCount(),
                        content.payload());
        if (!inserted.isEmpty()) {
            return ContentWriteOutcome.INSERTED;
        }
        var existing =
                db.sql.query(
                        """
                        select schema_version, shareholders_count, bucket_count, payload
                        from read_pot_balance_aggregate_content
                        where content_hash = ?
                        """,
                        (rs, n) ->
                                content.exactContentEquals(
                                        rs.getInt(1),
                                        rs.getInt(2),
                                        rs.getInt(3),
                                        rs.getBytes(4)),
                        hashBytes);
        if (existing.isEmpty() || !Boolean.TRUE.equals(existing.getFirst())) {
            throw new IllegalStateException(
                    "Divergent read_pot_balance_aggregate_content for hash " + content.hash());
        }
        return ContentWriteOutcome.REUSED;
    }

    private void insertOrVerifyAggregatePointer(
            UUID potId, long version, ContentHash expectedHash) {
        byte[] expectedBytes = expectedHash.bytes();
        var inserted =
                db.sql.query(
                        """
                        insert into read_pot_balance_aggregate(pot_id, pot_version, content_hash)
                        values (?, ?, ?)
                        on conflict (pot_id, pot_version) do nothing
                        returning 1
                        """,
                        (rs, n) -> rs.getInt(1),
                        potId,
                        version,
                        expectedBytes);
        if (!inserted.isEmpty()) {
            return;
        }
        var actual =
                db.sql.query(
                        "select content_hash from read_pot_balance_aggregate where pot_id = ? and pot_version = ?",
                        (rs, n) -> rs.getBytes(1),
                        potId,
                        version);
        if (actual.isEmpty() || !Arrays.equals(actual.getFirst(), expectedBytes)) {
            throw new IllegalStateException(
                    "Divergent read_pot_balance_aggregate for pot=" + potId + " version=" + version);
        }
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

    @Override
    public void closePublication() {
        SpringAttemptTransactions.closePublication(db.source);
    }
}
