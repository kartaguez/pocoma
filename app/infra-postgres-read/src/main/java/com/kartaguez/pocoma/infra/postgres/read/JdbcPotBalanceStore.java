package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateBinaryCodec;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateBinaryCodec.EncodedAggregate;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateContent;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec.EncodedBalance;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceContent;
import com.kartaguez.pocoma.domain.projection.balance.PotShareholderSet;
import com.kartaguez.pocoma.domain.projection.balance.PotShareholderSetBinaryCodec;
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
    private static final ReadAvailability.Family FAMILY = ReadAvailability.Family.POT_BALANCE_AVAILABLE;

    private final ReadJdbc db;
    private final ReadAvailability availability;

    public JdbcPotBalanceStore(DataSource source, SpringAttemptTransactions owner) {
        this(source, owner, false);
    }

    public JdbcPotBalanceStore(DataSource source, SpringAttemptTransactions owner, boolean publishAvailability) {
        this.db = new ReadJdbc(source, owner);
        this.availability = new ReadAvailability(db, publishAvailability);
    }

    private Optional<PotBalance> load(UUID potId, long version) {
        return db.sql
                .query(
                        """
                        select c.content_hash, c.schema_version, c.shareholders_count, c.payload,
                               s.set_hash, s.schema_version, s.shareholders_count, s.payload
                        from read_pot_balance b
                        join read_pot_balance_content c on c.content_hash = b.content_hash
                        join read_pot_shareholder_set s on s.set_hash = c.shareholder_set_hash
                        where b.pot_id = ? and b.pot_version = ?
                        """,
                        (rs, n) -> decodeBalance(
                                potId,
                                version,
                                rs.getBytes(1),
                                rs.getInt(2),
                                rs.getInt(3),
                                rs.getBytes(4),
                                rs.getBytes(5),
                                rs.getInt(6),
                                rs.getInt(7),
                                rs.getBytes(8)),
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
            byte[] payload,
            byte[] setHashBytes,
            int setSchemaVersion,
            int setShareholdersCount,
            byte[] setPayload) {
        try {
            PotShareholderSet shareholderSet = PotShareholderSetBinaryCodec.decode(
                    setSchemaVersion, setShareholdersCount, setPayload, ContentHash.of(setHashBytes));
            return PotBalanceBinaryCodec.decode(
                    potId,
                    version,
                    shareholderSet,
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
                        select c.content_hash, c.schema_version, c.shareholders_count, c.bucket_count, c.payload,
                               s.set_hash, s.schema_version, s.shareholders_count, s.payload
                        from read_pot_balance_aggregate a
                        join read_pot_balance_aggregate_content c on c.content_hash = a.content_hash
                        join read_pot_shareholder_set s on s.set_hash = c.shareholder_set_hash
                        where a.pot_id = ? and a.pot_version = ?
                        """,
                        (rs, n) -> decodeAggregate(
                                potId,
                                version,
                                rs.getBytes(1),
                                rs.getInt(2),
                                rs.getInt(3),
                                rs.getInt(4),
                                rs.getBytes(5),
                                rs.getBytes(6),
                                rs.getInt(7),
                                rs.getInt(8),
                                rs.getBytes(9)),
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
            byte[] payload,
            byte[] setHashBytes,
            int setSchemaVersion,
            int setShareholdersCount,
            byte[] setPayload) {
        try {
            PotShareholderSet shareholderSet = PotShareholderSetBinaryCodec.decode(
                    setSchemaVersion, setShareholdersCount, setPayload, ContentHash.of(setHashBytes));
            return PotBalanceAggregateBinaryCodec.decode(
                    potId,
                    version,
                    shareholderSet,
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
                        if (availability.enabled()) {
                            var event = availability.historicalEventPositionValid(FAMILY, potId, version);
                            if (actual.isEmpty() ? event.isPresent() : !event.orElse(false)) {
                                return ReadEvidence.DIVERGENT;
                            }
                            if (expected.isEmpty() && loadAggregate(potId, version).isPresent()) {
                                return ReadEvidence.DIVERGENT;
                            }
                        }
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
                    EncodedBalance encoded = PotBalanceBinaryCodec.encode(balance);
                    Duration encodeElapsed = Duration.ofNanos(System.nanoTime() - encodeStart);
                    insertOrVerifyShareholderSet(encoded.shareholderSet());
                    ContentWriteOutcome outcome = insertOrVerifyBalanceContent(encoded.content());
                    insertOrVerifyBalancePointer(
                            balance.potId(), balance.version(), encoded.content().hash());
                    return new WriteResult(outcome, encodeElapsed);
                });
    }

    @Override
    public PersistVersionResult insertOrVerifyVersionWithResult(
            PotBalance balance, Optional<PotBalanceAggregate> aggregate) {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(aggregate, "aggregate");
        if (aggregate.isEmpty()) {
            return db.write(
                    () -> {
                        long encodeStart = System.nanoTime();
                        EncodedBalance encoded = PotBalanceBinaryCodec.encode(balance);
                        Duration encodeElapsed = Duration.ofNanos(System.nanoTime() - encodeStart);
                        insertOrVerifyShareholderSet(encoded.shareholderSet());
                        ContentWriteOutcome outcome =
                                insertOrVerifyBalanceContent(encoded.content());
                        boolean inserted = insertOrVerifyBalancePointer(
                                balance.potId(), balance.version(), encoded.content().hash());
                        return new PersistVersionResult(
                                encoded.content().hash(),
                                new WriteResult(outcome, encodeElapsed),
                                Optional.empty(),
                                Optional.empty(),
                                inserted);
                    });
        }
        PotBalanceAggregate agg = aggregate.get();
        return db.write(
                () -> {
                    long balEncodeStart = System.nanoTime();
                    EncodedBalance balEnc = PotBalanceBinaryCodec.encode(balance);
                    Duration balEncodeElapsed = Duration.ofNanos(System.nanoTime() - balEncodeStart);

                    long aggEncodeStart = System.nanoTime();
                    EncodedAggregate aggEnc =
                            PotBalanceAggregateBinaryCodec.encodeWithShareholderSet(
                                    agg, balEnc.shareholderSet());
                    Duration aggEncodeElapsed = Duration.ofNanos(System.nanoTime() - aggEncodeStart);

                    insertOrVerifyShareholderSet(balEnc.shareholderSet());
                    if (!aggEnc.shareholderSet().hash().equals(balEnc.shareholderSet().hash())) {
                        insertOrVerifyShareholderSet(aggEnc.shareholderSet());
                    }

                    var outcomes =
                            insertOrVerifyContentPair(balEnc.content(), aggEnc.content());

                    boolean inserted = insertOrVerifyBalancePointer(
                            balance.potId(), balance.version(), balEnc.content().hash());
                    insertOrVerifyAggregatePointer(
                            agg.potId(), agg.version(), aggEnc.content().hash());

                    return new PersistVersionResult(
                            balEnc.content().hash(),
                            new WriteResult(outcomes[0], balEncodeElapsed),
                            Optional.of(aggEnc.content().hash()),
                            Optional.of(new WriteResult(outcomes[1], aggEncodeElapsed)),
                            inserted);
                });
    }

    private ContentWriteOutcome[] insertOrVerifyContentPair(
            PotBalanceContent balContent, PotBalanceAggregateContent aggContent) {
        byte[] balHashBytes = balContent.hash().bytes();
        byte[] aggHashBytes = aggContent.hash().bytes();
        var flags =
                db.sql.queryForObject(
                        """
                        with bal_c as (
                          insert into read_pot_balance_content(
                            content_hash, schema_version, shareholders_count, shareholder_set_hash, payload)
                          values (?, ?, ?, ?, ?)
                          on conflict (content_hash) do nothing
                          returning 1
                        ),
                        agg_c as (
                          insert into read_pot_balance_aggregate_content(
                            content_hash, schema_version, shareholders_count, bucket_count, shareholder_set_hash, payload)
                          values (?, ?, ?, ?, ?, ?)
                          on conflict (content_hash) do nothing
                          returning 1
                        )
                        select exists(select 1 from bal_c), exists(select 1 from agg_c)
                        """,
                        (rs, n) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)},
                        balHashBytes,
                        balContent.schemaVersion(),
                        balContent.shareholdersCount(),
                        balContent.shareholderSetHash().bytes(),
                        balContent.payload(),
                        aggHashBytes,
                        aggContent.schemaVersion(),
                        aggContent.shareholdersCount(),
                        aggContent.bucketCount(),
                        aggContent.shareholderSetHash().bytes(),
                        aggContent.payload());
        boolean balInserted = flags != null && flags[0];
        boolean aggInserted = flags != null && flags[1];
        if (!balInserted && !aggInserted) {
            verifyContentPairOnConflict(balContent, aggContent);
        } else {
            if (!balInserted) {
                verifyBalanceContentOnConflict(balContent);
            }
            if (!aggInserted) {
                verifyAggregateContentOnConflict(aggContent);
            }
        }
        return new ContentWriteOutcome[] {
            balInserted ? ContentWriteOutcome.INSERTED : ContentWriteOutcome.REUSED,
            aggInserted ? ContentWriteOutcome.INSERTED : ContentWriteOutcome.REUSED
        };
    }

    private void verifyContentPairOnConflict(
            PotBalanceContent balContent, PotBalanceAggregateContent aggContent) {
        var matched =
                db.sql.query(
                        """
                        select b.schema_version, b.shareholders_count, b.shareholder_set_hash, b.payload,
                               a.schema_version, a.shareholders_count, a.bucket_count, a.shareholder_set_hash, a.payload
                        from read_pot_balance_content b
                        join read_pot_balance_aggregate_content a on a.content_hash = ?
                        where b.content_hash = ?
                        """,
                        (rs, n) ->
                                balContent.exactContentEquals(
                                                rs.getInt(1),
                                                rs.getInt(2),
                                                ContentHash.of(rs.getBytes(3)),
                                                rs.getBytes(4))
                                        && aggContent.exactContentEquals(
                                                rs.getInt(5),
                                                rs.getInt(6),
                                                rs.getInt(7),
                                                ContentHash.of(rs.getBytes(8)),
                                                rs.getBytes(9)),
                        aggContent.hash().bytes(),
                        balContent.hash().bytes());
        if (matched.isEmpty() || !Boolean.TRUE.equals(matched.getFirst())) {
            // Re-check individually to emit the exact failing table diagnostic before rollback
            verifyBalanceContentOnConflict(balContent);
            verifyAggregateContentOnConflict(aggContent);
            throw new IllegalStateException(
                    "Divergent PotBalance or PotBalanceAggregate content for hash "
                            + balContent.hash());
        }
    }

    private void insertOrVerifyShareholderSet(PotShareholderSet set) {
        byte[] hashBytes = set.hash().bytes();
        var inserted =
                db.sql.query(
                        """
                        insert into read_pot_shareholder_set(
                          set_hash, schema_version, shareholders_count, payload)
                        values (?, ?, ?, ?)
                        on conflict (set_hash) do nothing
                        returning set_hash
                        """,
                        (rs, n) -> rs.getBytes(1),
                        hashBytes,
                        set.schemaVersion(),
                        set.shareholdersCount(),
                        set.payload());
        if (!inserted.isEmpty()) {
            return;
        }
        var existing =
                db.sql.query(
                        """
                        select schema_version, shareholders_count, payload
                        from read_pot_shareholder_set
                        where set_hash = ?
                        """,
                        (rs, n) ->
                                set.exactContentEquals(
                                        rs.getInt(1), rs.getInt(2), rs.getBytes(3)),
                        hashBytes);
        if (existing.isEmpty() || !Boolean.TRUE.equals(existing.getFirst())) {
            throw new IllegalStateException(
                    "Divergent read_pot_shareholder_set for hash " + set.hash());
        }
    }

    private ContentWriteOutcome insertOrVerifyBalanceContent(PotBalanceContent content) {
        byte[] hashBytes = content.hash().bytes();
        var inserted =
                db.sql.query(
                        """
                        insert into read_pot_balance_content(
                          content_hash, schema_version, shareholders_count, shareholder_set_hash, payload)
                        values (?, ?, ?, ?, ?)
                        on conflict (content_hash) do nothing
                        returning content_hash
                        """,
                        (rs, n) -> rs.getBytes(1),
                        hashBytes,
                        content.schemaVersion(),
                        content.shareholdersCount(),
                        content.shareholderSetHash().bytes(),
                        content.payload());
        if (!inserted.isEmpty()) {
            return ContentWriteOutcome.INSERTED;
        }
        verifyBalanceContentOnConflict(content);
        return ContentWriteOutcome.REUSED;
    }

    private void verifyBalanceContentOnConflict(PotBalanceContent content) {
        var existing =
                db.sql.query(
                        """
                        select schema_version, shareholders_count, shareholder_set_hash, payload
                        from read_pot_balance_content
                        where content_hash = ?
                        """,
                        (rs, n) ->
                                content.exactContentEquals(
                                        rs.getInt(1),
                                        rs.getInt(2),
                                        ContentHash.of(rs.getBytes(3)),
                                        rs.getBytes(4)),
                        content.hash().bytes());
        if (existing.isEmpty() || !Boolean.TRUE.equals(existing.getFirst())) {
            throw new IllegalStateException(
                    "Divergent read_pot_balance_content for hash " + content.hash());
        }
    }

    private boolean insertOrVerifyBalancePointer(UUID potId, long version, ContentHash expectedHash) {
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
            return true;
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
        return false;
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
    public void requireHashesStored(
            UUID potId,
            long version,
            Optional<ContentHash> expectedBalanceHash,
            Optional<ContentHash> expectedAggregateHash) {
        Objects.requireNonNull(potId, "potId");
        Objects.requireNonNull(expectedBalanceHash, "expectedBalanceHash");
        Objects.requireNonNull(expectedAggregateHash, "expectedAggregateHash");
        if (expectedBalanceHash.isEmpty() && expectedAggregateHash.isPresent()) {
            throw new IllegalArgumentException("Aggregate cannot be expected without balance");
        }
        db.write(
                () -> {
                    var rows =
                            db.sql.query(
                                    """
                                    select b.content_hash, a.content_hash
                                    from read_pot_balance b
                                    left join read_pot_balance_aggregate a
                                      on a.pot_id = b.pot_id and a.pot_version = b.pot_version
                                    where b.pot_id = ? and b.pot_version = ?
                                    """,
                                    (rs, n) -> new byte[][] {rs.getBytes(1), rs.getBytes(2)},
                                    potId,
                                    version);
                    if (expectedBalanceHash.isEmpty()) {
                        if (!rows.isEmpty()) {
                            throw new IllegalStateException(
                                    "Unexpected read_pot_balance row for deleted pot="
                                            + potId
                                            + " version="
                                            + version);
                        }
                        return null;
                    }
                    if (rows.isEmpty()) {
                        throw new IllegalStateException(
                                "Missing read_pot_balance for pot=" + potId + " version=" + version);
                    }
                    byte[] actualBal = rows.getFirst()[0];
                    byte[] actualAgg = rows.getFirst()[1];
                    if (!Arrays.equals(actualBal, expectedBalanceHash.get().bytes())) {
                        throw new IllegalStateException(
                                "Divergent read_pot_balance hash for pot="
                                        + potId
                                        + " version="
                                        + version);
                    }
                    if (expectedAggregateHash.isEmpty()) {
                        if (actualAgg != null) {
                            throw new IllegalStateException(
                                    "Unexpected read_pot_balance_aggregate for pot="
                                            + potId
                                            + " version="
                                            + version);
                        }
                    } else if (actualAgg == null
                            || !Arrays.equals(actualAgg, expectedAggregateHash.get().bytes())) {
                        throw new IllegalStateException(
                                "Missing or divergent read_pot_balance_aggregate hash for pot="
                                        + potId
                                        + " version="
                                        + version);
                    }
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
                                    select a.pot_version,
                                           c.content_hash, c.schema_version, c.shareholders_count,
                                           c.bucket_count, c.payload,
                                           s.set_hash, s.schema_version, s.shareholders_count, s.payload
                                    from read_pot_balance_aggregate a
                                    join read_pot_balance_aggregate_content c on c.content_hash = a.content_hash
                                    join read_pot_shareholder_set s on s.set_hash = c.shareholder_set_hash
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
                                            rs.getBytes(6),
                                            rs.getBytes(7),
                                            rs.getInt(8),
                                            rs.getInt(9),
                                            rs.getBytes(10)),
                                    potId,
                                    beforeVersionExclusive,
                                    minBound)
                            .stream()
                            .findFirst();
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
                    EncodedAggregate encoded =
                            PotBalanceAggregateBinaryCodec.encode(aggregate);
                    Duration encodeElapsed = Duration.ofNanos(System.nanoTime() - encodeStart);
                    insertOrVerifyShareholderSet(encoded.shareholderSet());
                    ContentWriteOutcome outcome =
                            insertOrVerifyAggregateContent(encoded.content());
                    insertOrVerifyAggregatePointer(
                            aggregate.potId(), aggregate.version(), encoded.content().hash());
                    return new WriteResult(outcome, encodeElapsed);
                });
    }

    private ContentWriteOutcome insertOrVerifyAggregateContent(PotBalanceAggregateContent content) {
        byte[] hashBytes = content.hash().bytes();
        var inserted =
                db.sql.query(
                        """
                        insert into read_pot_balance_aggregate_content(
                          content_hash, schema_version, shareholders_count, bucket_count, shareholder_set_hash, payload)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (content_hash) do nothing
                        returning content_hash
                        """,
                        (rs, n) -> rs.getBytes(1),
                        hashBytes,
                        content.schemaVersion(),
                        content.shareholdersCount(),
                        content.bucketCount(),
                        content.shareholderSetHash().bytes(),
                        content.payload());
        if (!inserted.isEmpty()) {
            return ContentWriteOutcome.INSERTED;
        }
        verifyAggregateContentOnConflict(content);
        return ContentWriteOutcome.REUSED;
    }

    private void verifyAggregateContentOnConflict(PotBalanceAggregateContent content) {
        var existing =
                db.sql.query(
                        """
                        select schema_version, shareholders_count, bucket_count, shareholder_set_hash, payload
                        from read_pot_balance_aggregate_content
                        where content_hash = ?
                        """,
                        (rs, n) ->
                                content.exactContentEquals(
                                        rs.getInt(1),
                                        rs.getInt(2),
                                        rs.getInt(3),
                                        ContentHash.of(rs.getBytes(4)),
                                        rs.getBytes(5)),
                        content.hash().bytes());
        if (existing.isEmpty() || !Boolean.TRUE.equals(existing.getFirst())) {
            throw new IllegalStateException(
                    "Divergent read_pot_balance_aggregate_content for hash " + content.hash());
        }
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
    public void requireCompletionReady(UUID pot, long version, Optional<ContentHash> balanceHash,
            Optional<ContentHash> aggregateHash, boolean inserted) {
        requireHashesStored(pot, version, balanceHash, aggregateHash);
        db.write(() -> {
            availability.requireHistoricalEvent(FAMILY, pot, version, balanceHash.isPresent() && !inserted);
            return null;
        });
    }

    @Override
    public void closePublication(UUID pot, long version, boolean pointerInserted) {
        availability.closeAndPublish(ReadAvailability.Publication.historical(
                FAMILY, pot, version, availability.enabled() && pointerInserted));
    }

    @Override
    public void closePublication() {
        SpringAttemptTransactions.closePublication(db.source);
    }
}
