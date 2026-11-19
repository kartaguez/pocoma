package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.radix.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;

import javax.sql.DataSource;

public final class JdbcPotVersionStore implements PotVersionStore {
    private static final int NODE_INSERT_CHUNK_SIZE = 100;

    private static final String FULL_READ_R1_SQL = """
            with recursive manifest as (
              select pv.pot_id, pv.pot_version, pv.schema_version, pv.header_version, pv.details_version,
                     pv.deleted, pv.shareholders_count, pv.expenses_count,
                     pv.shareholders_root, pv.expenses_root,
                     pv.authorized_users_hash, pv.attached_active_users_hash,
                     au.user_ids as authorized_user_ids,
                     atu.user_ids as attached_active_user_ids
              from read_pot_version pv
              left join read_pot_user_set au on au.set_hash = pv.authorized_users_hash
              left join read_pot_user_set atu on atu.set_hash = pv.attached_active_users_hash
              where pv.pot_id = ? and pv.pot_version = ?
            ),
            tree as (
              select n.node_hash, n.schema_version, n.node_kind, n.tree_kind,
                     n.entry_count, n.prefix, n.child_hashes, n.payload
              from manifest m
              join read_pot_version_node n on n.node_hash in (m.shareholders_root, m.expenses_root)
              union all
              select c.node_hash, c.schema_version, c.node_kind, c.tree_kind,
                     c.entry_count, c.prefix, c.child_hashes, c.payload
              from tree t
              join lateral unnest(t.child_hashes) as ch(h) on t.node_kind = 'I'
              join read_pot_version_node c on c.node_hash = ch.h
            )
            select 0 as row_kind,
                   m.pot_id, m.pot_version, m.schema_version, m.header_version, m.details_version, m.deleted,
                   m.shareholders_count, m.expenses_count, m.shareholders_root, m.expenses_root,
                   m.authorized_users_hash, m.attached_active_users_hash,
                   m.authorized_user_ids, m.attached_active_user_ids,
                   null::bytea as node_hash, null::integer as node_schema_version,
                   null::char(1) as node_kind, null::char(1) as tree_kind,
                   null::integer as entry_count, null::text as prefix,
                   null::bytea[] as child_hashes, null::bytea as payload
            from manifest m
            union all
            select 1 as row_kind,
                   null::uuid, null::bigint, null::integer, null::bigint, null::bigint, null::boolean,
                   null::integer, null::integer, null::bytea, null::bytea,
                   null::bytea, null::bytea,
                   null::uuid[], null::uuid[],
                   t.node_hash, t.schema_version, t.node_kind, t.tree_kind,
                   t.entry_count, t.prefix, t.child_hashes, t.payload
            from tree t
            """;

    private static final String TARGETED_SHAREHOLDERS_R3A_SQL = """
            with recursive manifest as (
              select pv.pot_id, pv.pot_version, pv.schema_version, pv.header_version, pv.details_version,
                     pv.deleted, pv.shareholders_count, pv.expenses_count,
                     pv.shareholders_root, pv.expenses_root,
                     pv.authorized_users_hash, pv.attached_active_users_hash,
                     au.user_ids as authorized_user_ids,
                     atu.user_ids as attached_active_user_ids
              from read_pot_version pv
              left join read_pot_user_set au on au.set_hash = pv.authorized_users_hash
              left join read_pot_user_set atu on atu.set_hash = pv.attached_active_users_hash
              where pv.pot_id = ? and pv.pot_version = ?
            ),
            tree as (
              select n.node_hash, n.schema_version, n.node_kind, n.tree_kind,
                     n.entry_count, n.prefix, n.child_hashes, n.payload
              from manifest m
              join read_pot_version_node n on n.node_hash = m.shareholders_root
              union all
              select c.node_hash, c.schema_version, c.node_kind, c.tree_kind,
                     c.entry_count, c.prefix, c.child_hashes, c.payload
              from tree t
              join lateral unnest(t.child_hashes) as ch(h) on t.node_kind = 'I'
              join read_pot_version_node c on c.node_hash = ch.h
            )
            select 0 as row_kind,
                   m.pot_id, m.pot_version, m.schema_version, m.header_version, m.details_version, m.deleted,
                   m.shareholders_count, m.expenses_count, m.shareholders_root, m.expenses_root,
                   m.authorized_users_hash, m.attached_active_users_hash,
                   m.authorized_user_ids, m.attached_active_user_ids,
                   null::bytea as node_hash, null::integer as node_schema_version,
                   null::char(1) as node_kind, null::char(1) as tree_kind,
                   null::integer as entry_count, null::text as prefix,
                   null::bytea[] as child_hashes, null::bytea as payload
            from manifest m
            union all
            select 1 as row_kind,
                   null::uuid, null::bigint, null::integer, null::bigint, null::bigint, null::boolean,
                   null::integer, null::integer, null::bytea, null::bytea,
                   null::bytea, null::bytea,
                   null::uuid[], null::uuid[],
                   t.node_hash, t.schema_version, t.node_kind, t.tree_kind,
                   t.entry_count, t.prefix, t.child_hashes, t.payload
            from tree t
            """;

    private static final String MANIFEST_SELECT_COLUMNS = """
            select pv.pot_id, pv.pot_version, pv.schema_version, pv.header_version, pv.details_version,
                   pv.deleted, pv.shareholders_count, pv.expenses_count,
                   pv.shareholders_root, pv.expenses_root,
                   pv.authorized_users_hash, pv.attached_active_users_hash,
                   au.user_ids as authorized_user_ids,
                   atu.user_ids as attached_active_user_ids
            from read_pot_version pv
            left join read_pot_user_set au on au.set_hash = pv.authorized_users_hash
            left join read_pot_user_set atu on atu.set_hash = pv.attached_active_users_hash
            """;

    private final ReadJdbc db;

    public JdbcPotVersionStore(DataSource source, SpringAttemptTransactions owner) {
        this.db = new ReadJdbc(source, owner);
    }

    @Override
    public OptionalLong currentVersion(UUID pot, RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    var versions = db.sql.query(
                            "select pot_version from read_current_pot where pot_id=?",
                            (r, n) -> r.getLong(1),
                            pot);
                    return versions.isEmpty()
                            ? OptionalLong.empty()
                            : OptionalLong.of(versions.getFirst());
                });
    }

    @Override
    public Optional<PotVersion> read(UUID pot, long version, RecoveryReadContext context) {
        return db.read(context, () -> load(pot, version));
    }

    @Override
    public Optional<PotVersion> readLatestBefore(
            UUID pot,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(pot);
                    if (beforeVersionExclusive <= 1 || minVersionInclusive >= beforeVersionExclusive) {
                        return Optional.empty();
                    }
                    long minBound = Math.max(1L, minVersionInclusive);
                    var versionOpt = db.sql.query(
                                    "select pot_version from read_pot_version where pot_id=? and pot_version<? and pot_version>=? order by pot_version desc limit 1",
                                    (r, n) -> r.getLong(1),
                                    pot,
                                    beforeVersionExclusive,
                                    minBound)
                            .stream()
                            .findFirst();
                    return versionOpt.flatMap(v -> load(pot, v));
                });
    }

    @Override
    public Optional<PotVersionManifest> readManifestLatestBefore(
            UUID pot,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(pot);
                    if (beforeVersionExclusive <= 1 || minVersionInclusive >= beforeVersionExclusive) {
                        return Optional.empty();
                    }
                    long minBound = Math.max(1L, minVersionInclusive);
                    return db.sql.query(
                                    MANIFEST_SELECT_COLUMNS
                                            + " where pv.pot_id=? and pv.pot_version<? and pv.pot_version>=? order by pv.pot_version desc limit 1",
                                    (rs, n) -> mapManifest(rs, 1),
                                    pot,
                                    beforeVersionExclusive,
                                    minBound)
                            .stream()
                            .findFirst();
                });
    }

    @Override
    public Optional<PotShareholdersView> readShareholders(
            UUID pot, long version, RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(pot);
                    final PotVersionManifest[] manifestHolder = new PotVersionManifest[1];
                    final Map<ContentHash, Radix16Node> nodes = new HashMap<>();
                    db.sql.query(
                            TARGETED_SHAREHOLDERS_R3A_SQL,
                            rs -> {
                                int rowKind = rs.getInt(1);
                                if (rowKind == 0) {
                                    if (manifestHolder[0] != null) {
                                        throw new IllegalStateException("Duplicate manifest row");
                                    }
                                    manifestHolder[0] = mapManifest(rs, 2);
                                } else {
                                    Radix16Node node = mapNode(rs, 16);
                                    Radix16Node prev = nodes.put(node.hash(), node);
                                    if (prev != null && !prev.exactContentEquals(node)) {
                                        throw new IllegalStateException("Inconsistent duplicate node in R3a query: " + node.hash());
                                    }
                                }
                            },
                            pot,
                            version);
                    PotVersionManifest manifest = manifestHolder[0];
                    if (manifest == null) {
                        return Optional.empty();
                    }
                    if (!pot.equals(manifest.potId()) || version != manifest.version()) {
                        throw new IllegalStateException("Mixed PotVersion key");
                    }
                    List<PotVersion.Shareholder> shareholders =
                            Radix16Tree.reconstructShareholders(manifest.shareholdersRoot(), nodes);
                    if (shareholders.size() != manifest.shareholdersCount()) {
                        throw new IllegalStateException("Shareholders count mismatch");
                    }
                    return Optional.of(
                            new PotShareholdersView(
                                    manifest.potId(),
                                    manifest.version(),
                                    manifest.deleted(),
                                    manifest.authorizedUserIds(),
                                    manifest.attachedActiveUserIds(),
                                    shareholders));
                });
    }

    @Override
    public Map<UUID, PotVersion.Shareholder> readShareholdersByIds(
            UUID pot, long version, Collection<UUID> ids, RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(pot);
                    if (ids == null || ids.isEmpty()) {
                        return Map.of();
                    }
                    ContentHash root = queryRootHash(pot, version, "shareholders_root");
                    if (root == null) {
                        return Map.of();
                    }
                    Map<UUID, Radix16Node> reachedLeaves = descendToLeaves(root, Radix16Codec.TREE_SHAREHOLDERS, ids);
                    Map<UUID, PotVersion.Shareholder> found = new HashMap<>(reachedLeaves.size() * 2);
                    for (Map.Entry<UUID, Radix16Node> entry : reachedLeaves.entrySet()) {
                        Radix16Codec.findShareholderInLeaf(entry.getValue(), entry.getKey())
                                .ifPresent(s -> found.put(entry.getKey(), s));
                    }
                    return Collections.unmodifiableMap(found);
                });
    }

    @Override
    public Map<UUID, PotVersion.Expense> readExpensesByIds(
            UUID pot, long version, Collection<UUID> ids, RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    Objects.requireNonNull(pot);
                    if (ids == null || ids.isEmpty()) {
                        return Map.of();
                    }
                    ContentHash root = queryRootHash(pot, version, "expenses_root");
                    if (root == null) {
                        return Map.of();
                    }
                    Map<UUID, Radix16Node> reachedLeaves = descendToLeaves(root, Radix16Codec.TREE_EXPENSES, ids);
                    Map<UUID, PotVersion.Expense> found = new HashMap<>(reachedLeaves.size() * 2);
                    for (Map.Entry<UUID, Radix16Node> entry : reachedLeaves.entrySet()) {
                        Radix16Codec.findExpenseInLeaf(entry.getValue(), entry.getKey())
                                .ifPresent(e -> found.put(entry.getKey(), e));
                    }
                    return Collections.unmodifiableMap(found);
                });
    }

    @Override
    public Map<ContentHash, Radix16Node> loadNodes(
            Collection<ContentHash> hashes, RecoveryReadContext context) {
        return db.read(context, () -> fetchNodesByHashes(hashes));
    }

    @Override
    public ReadEvidence evidence(PotVersion expected, RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    try {
                        var actual = load(expected.potId(), expected.version());
                        if (actual.isEmpty()) {
                            return ReadEvidence.MISSING;
                        }
                        if (!actual.get().equals(expected)) {
                            return ReadEvidence.DIVERGENT;
                        }
                        var current = db.sql.query(
                                "select pot_version from read_current_pot where pot_id=?",
                                (r, n) -> r.getLong(1),
                                expected.potId());
                        if (current.isEmpty()) {
                            return ReadEvidence.MISSING;
                        }
                        long version = current.getFirst();
                        if (version < expected.version()) {
                            return ReadEvidence.DIVERGENT;
                        }
                        if (version > expected.version() && load(expected.potId(), version).isEmpty()) {
                            return ReadEvidence.DIVERGENT;
                        }
                        return ReadEvidence.SATISFIED;
                    } catch (IllegalStateException | IllegalArgumentException positiveCorruption) {
                        return ReadEvidence.DIVERGENT;
                    }
                });
    }

    @Override
    public void insertAndAdvance(PotVersion value) {
        publishPrepared(PotVersionWritePlan.fromFull(value));
    }

    @Override
    public void publishPrepared(PotVersionWritePlan plan) {
        Objects.requireNonNull(plan, "plan required");
        db.write(
                () -> {
                    for (PotUserSet userSet : plan.userSetsToInsert()) {
                        insertOrVerifyUserSet(userSet);
                    }
                    for (List<Radix16Node> level : plan.nodeLevelsBottomUp()) {
                        insertOrVerifyNodeLevel(level);
                    }
                    insertOrVerifyManifest(plan.manifest());
                    advanceCurrentPot(plan.manifest().potId(), plan.manifest().version());
                    return null;
                });
    }

    @Override
    public void requireStored(PotVersion value) {
        db.write(
                () -> {
                    ReadJdbc.same(value, load(value.potId(), value.version()).orElse(null));
                    var current = db.sql.queryForObject(
                            "select pot_version from read_current_pot where pot_id=?",
                            Long.class,
                            value.potId());
                    if (current == null
                            || current < value.version()
                            || load(value.potId(), current).isEmpty()) {
                        throw new IllegalStateException("Incomplete CurrentPot");
                    }
                    return null;
                });
    }

    @Override
    public void requireManifestStored(PotVersionManifest expected) {
        Objects.requireNonNull(expected, "expected manifest required");
        db.write(
                () -> {
                    ReadJdbc.same(expected, loadManifest(expected.potId(), expected.version()).orElse(null));
                    var current = db.sql.queryForObject(
                            "select pot_version from read_current_pot where pot_id=?",
                            Long.class,
                            expected.potId());
                    if (current == null
                            || current < expected.version()
                            || (current > expected.version()
                                    && loadManifest(expected.potId(), current).isEmpty())) {
                        throw new IllegalStateException("Incomplete CurrentPot");
                    }
                    return null;
                });
    }

    @Override
    public void closePublication() {
        SpringAttemptTransactions.closePublication(db.source);
    }

    // =========================================================================
    // Internal write helpers (caller READ_COMMITTED transaction)
    // =========================================================================

    private void insertOrVerifyUserSet(PotUserSet expected) {
        List<ContentHash> inserted = db.sql.query(
                con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "insert into read_pot_user_set(set_hash,schema_version,user_count,user_ids) "
                                    + "values (?,?,?,?) on conflict (set_hash) do nothing returning set_hash");
                    ps.setBytes(1, expected.hash().bytes());
                    ps.setInt(2, expected.schemaVersion());
                    ps.setInt(3, expected.userCount());
                    ps.setArray(4, con.createArrayOf("uuid", expected.userIds().toArray(UUID[]::new)));
                    return ps;
                },
                (rs, n) -> ContentHash.of(rs.getBytes(1)));

        if (inserted.isEmpty()) {
            PotUserSet actual = db.sql.query(
                            "select set_hash, schema_version, user_ids from read_pot_user_set where set_hash=?",
                            (rs, n) -> PotUserSet.fromStored(
                                    ContentHash.of(rs.getBytes(1)),
                                    rs.getInt(2),
                                    readUuidArray(rs, 3)),
                            expected.hash().bytes())
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (actual == null || !expected.exactContentEquals(actual)) {
                throw new IllegalStateException("Divergent read_pot_user_set content for hash " + expected.hash());
            }
        }
    }

    private void insertOrVerifyNodeLevel(List<Radix16Node> level) {
        for (int start = 0; start < level.size(); start += NODE_INSERT_CHUNK_SIZE) {
            List<Radix16Node> chunk = level.subList(start, Math.min(start + NODE_INSERT_CHUNK_SIZE, level.size()));
            insertOrVerifyNodeChunk(chunk);
        }
    }

    private void insertOrVerifyNodeChunk(List<Radix16Node> chunk) {
        StringBuilder sql = new StringBuilder(
                "insert into read_pot_version_node(node_hash,schema_version,node_kind,tree_kind,entry_count,prefix,child_hashes,payload) values ");
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,?,?,?,?,?)");
        }
        sql.append(" on conflict (node_hash) do nothing returning node_hash");

        List<ContentHash> inserted = db.sql.query(
                con -> {
                    PreparedStatement ps = con.prepareStatement(sql.toString());
                    int param = 1;
                    for (Radix16Node node : chunk) {
                        ps.setBytes(param++, node.hash().bytes());
                        ps.setInt(param++, node.schemaVersion());
                        ps.setString(param++, String.valueOf(node.nodeKind()));
                        ps.setString(param++, String.valueOf(node.treeKind()));
                        ps.setInt(param++, node.entryCount());
                        ps.setString(param++, node.prefix());
                        if (node.childHashes() == null) {
                            ps.setNull(param++, Types.ARRAY);
                        } else {
                            byte[][] children = node.childHashes().stream()
                                    .map(ContentHash::bytes)
                                    .toArray(byte[][]::new);
                            ps.setArray(param++, con.createArrayOf("bytea", children));
                        }
                        ps.setBytes(param++, node.payload());
                    }
                    return ps;
                },
                (rs, n) -> ContentHash.of(rs.getBytes(1)));

        if (inserted.size() == chunk.size()) {
            return;
        }
        Set<ContentHash> insertedSet = new HashSet<>(inserted);
        List<Radix16Node> conflicted = new ArrayList<>();
        List<ContentHash> conflictedHashes = new ArrayList<>();
        for (Radix16Node node : chunk) {
            if (!insertedSet.contains(node.hash())) {
                conflicted.add(node);
                conflictedHashes.add(node.hash());
            }
        }
        Map<ContentHash, Radix16Node> actualByHash = fetchNodesByHashes(conflictedHashes);
        for (Radix16Node expected : conflicted) {
            Radix16Node actual = actualByHash.get(expected.hash());
            if (actual == null || !expected.exactContentEquals(actual)) {
                throw new IllegalStateException("Divergent read_pot_version_node content for hash " + expected.hash());
            }
        }
    }

    private void insertOrVerifyManifest(PotVersionManifest m) {
        List<Integer> inserted = db.sql.query(
                con -> {
                    PreparedStatement ps = con.prepareStatement("""
                            insert into read_pot_version(
                              pot_id, pot_version, schema_version, header_version, details_version, deleted,
                              shareholders_count, expenses_count, shareholders_root, expenses_root,
                              authorized_users_hash, attached_active_users_hash)
                            values (?,?,?,?,?,?,?,?,?,?,?,?)
                            on conflict (pot_id, pot_version) do nothing
                            returning 1""");
                    ps.setObject(1, m.potId());
                    ps.setLong(2, m.version());
                    ps.setInt(3, m.schemaVersion());
                    ps.setLong(4, m.headerVersion());
                    ps.setLong(5, m.detailsVersion());
                    ps.setBoolean(6, m.deleted());
                    ps.setInt(7, m.shareholdersCount());
                    ps.setInt(8, m.expensesCount());
                    setOptionalHash(ps, 9, m.shareholdersRoot());
                    setOptionalHash(ps, 10, m.expensesRoot());
                    setOptionalHash(ps, 11, m.authorizedUsersHash());
                    setOptionalHash(ps, 12, m.attachedActiveUsersHash());
                    return ps;
                },
                (rs, n) -> rs.getInt(1));

        if (inserted.isEmpty()) {
            PotVersionManifest actual = loadManifest(m.potId(), m.version()).orElse(null);
            ReadJdbc.same(m, actual);
        }
    }

    private void advanceCurrentPot(UUID potId, long version) {
        db.sql.update(
                "insert into read_current_pot(pot_id,pot_version) values (?,?) "
                        + "on conflict (pot_id) do update set pot_version=excluded.pot_version "
                        + "where read_current_pot.pot_version < excluded.pot_version",
                potId,
                version);
    }

    // =========================================================================
    // Internal read helpers (caller REPEATABLE_READ or READ_COMMITTED tx)
    // =========================================================================

    private Optional<PotVersion> load(UUID pot, long version) {
        final PotVersionManifest[] manifestHolder = new PotVersionManifest[1];
        final Map<ContentHash, Radix16Node> nodes = new HashMap<>();

        db.sql.query(
                FULL_READ_R1_SQL,
                rs -> {
                    int rowKind = rs.getInt(1);
                    if (rowKind == 0) {
                        if (manifestHolder[0] != null) {
                            throw new IllegalStateException("Duplicate manifest row");
                        }
                        manifestHolder[0] = mapManifest(rs, 2);
                    } else {
                        Radix16Node node = mapNode(rs, 16);
                        Radix16Node prev = nodes.put(node.hash(), node);
                        if (prev != null && !prev.exactContentEquals(node)) {
                            throw new IllegalStateException("Inconsistent duplicate node in R1 query: " + node.hash());
                        }
                    }
                },
                pot,
                version);

        PotVersionManifest manifest = manifestHolder[0];
        if (manifest == null) {
            return Optional.empty();
        }
        if (!pot.equals(manifest.potId()) || version != manifest.version()) {
            throw new IllegalStateException("Mixed PotVersion key");
        }
        List<PotVersion.Shareholder> shareholders =
                Radix16Tree.reconstructShareholders(manifest.shareholdersRoot(), nodes);
        List<PotVersion.Expense> expenses =
                Radix16Tree.reconstructExpenses(manifest.expensesRoot(), nodes);
        if (shareholders.size() != manifest.shareholdersCount()) {
            throw new IllegalStateException("Shareholders count mismatch");
        }
        if (expenses.size() != manifest.expensesCount()) {
            throw new IllegalStateException("Expenses count mismatch");
        }
        return Optional.of(
                new PotVersion(
                        manifest.potId(),
                        manifest.version(),
                        manifest.headerVersion(),
                        manifest.detailsVersion(),
                        manifest.deleted(),
                        shareholders,
                        expenses,
                        manifest.authorizedUserIds(),
                        manifest.attachedActiveUserIds()));
    }

    private Optional<PotVersionManifest> loadManifest(UUID pot, long version) {
        return db.sql.query(
                        MANIFEST_SELECT_COLUMNS + " where pv.pot_id=? and pv.pot_version=?",
                        (rs, n) -> mapManifest(rs, 1),
                        pot,
                        version)
                .stream()
                .findFirst();
    }

    private ContentHash queryRootHash(UUID pot, long version, String column) {
        List<byte[]> rows = db.sql.query(
                "select " + column + " from read_pot_version where pot_id=? and pot_version=?",
                (rs, n) -> rs.getBytes(1),
                pot,
                version);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return null;
        }
        return ContentHash.of(rows.getFirst());
    }

    private record DescentTarget(UUID id, int depth) {}

    private Map<UUID, Radix16Node> descendToLeaves(
            ContentHash rootHash, char expectedTreeKind, Collection<UUID> ids) {
        Set<UUID> uniqueIds = new HashSet<>(ids);
        Map<ContentHash, List<DescentTarget>> frontier = new LinkedHashMap<>();
        List<DescentTarget> initial = new ArrayList<>(uniqueIds.size());
        for (UUID id : uniqueIds) {
            initial.add(new DescentTarget(Objects.requireNonNull(id), 0));
        }
        frontier.put(rootHash, initial);

        Map<UUID, Radix16Node> reachedLeaves = new HashMap<>(uniqueIds.size() * 2);
        int rounds = 0;
        while (!frontier.isEmpty()) {
            if (++rounds > Radix16Tree.MAX_DEPTH + 1) {
                throw new IllegalStateException("Radix-16 descent exceeded max depth");
            }
            Map<ContentHash, Radix16Node> fetched = fetchNodesByHashes(frontier.keySet());
            Map<ContentHash, List<DescentTarget>> nextFrontier = new LinkedHashMap<>();
            for (Map.Entry<ContentHash, List<DescentTarget>> entry : frontier.entrySet()) {
                Radix16Node node = fetched.get(entry.getKey());
                if (node == null) {
                    throw new IllegalStateException("Missing Radix-16 node during descent: " + entry.getKey());
                }
                if (node.treeKind() != expectedTreeKind) {
                    throw new IllegalStateException("Cross-tree node during descent: " + node.treeKind());
                }
                if (node.isLeaf()) {
                    for (DescentTarget target : entry.getValue()) {
                        reachedLeaves.put(target.id(), node);
                    }
                } else {
                    String prefix = node.prefix();
                    int prefixLen = prefix.length();
                    for (DescentTarget target : entry.getValue()) {
                        if (Radix16Tree.firstDivergence(target.id(), target.depth(), prefix) == prefixLen) {
                            int splitDepth = target.depth() + prefixLen;
                            int nib = Radix16Codec.uuidNibble(target.id(), splitDepth);
                            int childIdx = node.childIndexForNibble(nib);
                            if (childIdx >= 0) {
                                ContentHash childHash = node.childHashes().get(childIdx);
                                nextFrontier.computeIfAbsent(childHash, k -> new ArrayList<>())
                                        .add(new DescentTarget(target.id(), splitDepth + 1));
                            }
                        }
                    }
                }
            }
            frontier = nextFrontier;
        }
        return reachedLeaves;
    }

    private Map<ContentHash, Radix16Node> fetchNodesByHashes(Collection<ContentHash> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return Map.of();
        }
        List<ContentHash> distinct = new ArrayList<>(new LinkedHashSet<>(hashes));
        byte[][] rawHashes = distinct.stream().map(ContentHash::bytes).toArray(byte[][]::new);
        Map<ContentHash, Radix16Node> result = new HashMap<>(distinct.size() * 2);
        db.sql.query(
                (Connection con) -> {
                    PreparedStatement ps = con.prepareStatement(
                            "select node_hash, schema_version, node_kind, tree_kind, entry_count, prefix, child_hashes, payload "
                                    + "from read_pot_version_node where node_hash = any(?)");
                    ps.setArray(1, con.createArrayOf("bytea", rawHashes));
                    return ps;
                },
                rs -> {
                    Radix16Node node = mapNode(rs, 1);
                    result.put(node.hash(), node);
                });
        return result;
    }

    private static PotVersionManifest mapManifest(ResultSet rs, int colOffset) throws SQLException {
        UUID potId = rs.getObject(colOffset, UUID.class);
        long potVersion = rs.getLong(colOffset + 1);
        int schemaVersion = rs.getInt(colOffset + 2);
        long headerVersion = rs.getLong(colOffset + 3);
        long detailsVersion = rs.getLong(colOffset + 4);
        boolean deleted = rs.getBoolean(colOffset + 5);
        int shCount = rs.getInt(colOffset + 6);
        int exCount = rs.getInt(colOffset + 7);
        ContentHash shRoot = optionalHash(rs.getBytes(colOffset + 8));
        ContentHash exRoot = optionalHash(rs.getBytes(colOffset + 9));
        ContentHash authHash = optionalHash(rs.getBytes(colOffset + 10));
        ContentHash attHash = optionalHash(rs.getBytes(colOffset + 11));
        Set<UUID> authUsers = readUuidSet(rs, colOffset + 12);
        Set<UUID> attUsers = readUuidSet(rs, colOffset + 13);
        return new PotVersionManifest(
                potId,
                potVersion,
                schemaVersion,
                headerVersion,
                detailsVersion,
                deleted,
                shCount,
                exCount,
                shRoot,
                exRoot,
                authHash,
                attHash,
                authUsers,
                attUsers);
    }

    private static Radix16Node mapNode(ResultSet rs, int colOffset) throws SQLException {
        ContentHash hash = ContentHash.of(rs.getBytes(colOffset));
        int schemaVersion = rs.getInt(colOffset + 1);
        char nodeKind = rs.getString(colOffset + 2).charAt(0);
        char treeKind = rs.getString(colOffset + 3).charAt(0);
        int entryCount = rs.getInt(colOffset + 4);
        String prefix = rs.getString(colOffset + 5);
        List<ContentHash> childHashes = readHashArray(rs, colOffset + 6);
        byte[] payload = rs.getBytes(colOffset + 7);
        return new Radix16Node(hash, schemaVersion, nodeKind, treeKind, entryCount, prefix, childHashes, payload);
    }

    private static ContentHash optionalHash(byte[] bytes) {
        return bytes == null ? null : ContentHash.of(bytes);
    }

    private static void setOptionalHash(PreparedStatement ps, int index, ContentHash hash) throws SQLException {
        if (hash == null) {
            ps.setNull(index, Types.BINARY);
        } else {
            ps.setBytes(index, hash.bytes());
        }
    }

    private static List<UUID> readUuidArray(ResultSet rs, int col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) {
            return List.of();
        }
        try {
            Object[] raw = (Object[]) arr.getArray();
            List<UUID> out = new ArrayList<>(raw.length);
            for (Object o : raw) {
                out.add((UUID) o);
            }
            return out;
        } finally {
            arr.free();
        }
    }

    private static Set<UUID> readUuidSet(ResultSet rs, int col) throws SQLException {
        List<UUID> list = readUuidArray(rs, col);
        if (list.isEmpty()) {
            return Set.of();
        }
        TreeSet<UUID> ordered = new TreeSet<>(SubobjectKey::compareUuid);
        ordered.addAll(list);
        return Collections.unmodifiableSet(ordered);
    }

    private static List<ContentHash> readHashArray(ResultSet rs, int col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) {
            return null;
        }
        try {
            Object[] raw = (Object[]) arr.getArray();
            List<ContentHash> out = new ArrayList<>(raw.length);
            for (Object o : raw) {
                out.add(ContentHash.of((byte[]) o));
            }
            return out;
        } finally {
            arr.free();
        }
    }
}
