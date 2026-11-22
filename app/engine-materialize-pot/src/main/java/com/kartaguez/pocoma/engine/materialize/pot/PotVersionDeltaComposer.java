package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.domain.pot.revision.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.SubobjectKind;
import com.kartaguez.pocoma.engine.materialize.pot.PotVersionSource.StateDelta;
import com.kartaguez.pocoma.port.projection.PotVersionManifest;
import java.util.*;

/** Pure state composition P_N = P_K (+) Delta_{K -> N} with global-effect detection (D2). */
final class PotVersionDeltaComposer {
    private PotVersionDeltaComposer() {}

    record ComposedDelta(
            long nextHeaderVersion,
            long nextDetailsVersion,
            boolean nextDeleted,
            int nextShareholdersCount,
            int nextExpensesCount,
            Set<UUID> nextAuthorizedUsers,
            Set<UUID> nextAttachedUsers,
            Map<UUID, PotVersion.Shareholder> shareholderUpserts,
            Map<UUID, PotVersion.Expense> expenseUpserts,
            List<SubobjectKey> changedKeys,
            PotVersion contentShell) {}

    static Set<UUID> affectedShareholderIds(StateDelta delta) {
        if (delta.shareholderHeaders().isEmpty()
                && delta.shareholderDetails().isEmpty()
                && delta.shareholderWeights().isEmpty()) {
            return Set.of();
        }
        var ids = new HashSet<UUID>();
        ids.addAll(delta.shareholderHeaders().keySet());
        ids.addAll(delta.shareholderDetails().keySet());
        ids.addAll(delta.shareholderWeights().keySet());
        return ids;
    }

    static Set<UUID> affectedExpenseIds(StateDelta delta) {
        if (delta.expenseHeaders().isEmpty()
                && delta.expenseDetails().isEmpty()
                && delta.expenseFinancials().isEmpty()) {
            return Set.of();
        }
        var ids = new HashSet<UUID>();
        ids.addAll(delta.expenseHeaders().keySet());
        ids.addAll(delta.expenseDetails().keySet());
        ids.addAll(delta.expenseFinancials().keySet());
        return ids;
    }

    static Optional<ComposedDelta> composeDelta(
            PotVersionManifest previous,
            long targetVersion,
            Map<UUID, PotVersion.Shareholder> prevShareholders,
            Map<UUID, PotVersion.Expense> prevExpenses,
            StateDelta delta) {
        if (hasGlobalShareholderEffect(prevShareholders, delta)) {
            return Optional.empty();
        }
        UUID potId = previous.potId();
        var changedKeys = new ArrayList<SubobjectKey>(delta.changedSubobjectCount());

        long headerVersion = previous.headerVersion();
        boolean deleted = previous.deleted();
        if (delta.potHeader().isPresent()) {
            var h = delta.potHeader().get();
            requireMonotone("pot_header", previous.headerVersion(), h.localVersion());
            headerVersion = h.localVersion();
            deleted = h.deleted();
            changedKeys.add(new SubobjectKey(potId, SubobjectKind.POT_HEADER, potId, headerVersion));
        }

        long detailsVersion = previous.detailsVersion();
        if (delta.potDetails().isPresent()) {
            var d = delta.potDetails().get();
            requireMonotone("pot_details", previous.detailsVersion(), d.localVersion());
            detailsVersion = d.localVersion();
            changedKeys.add(new SubobjectKey(potId, SubobjectKind.POT_DETAILS, potId, detailsVersion));
        }

        var attached = new TreeSet<UUID>(SubobjectKey::compareUuid);
        attached.addAll(previous.attachedActiveUserIds());
        var authorized = new TreeSet<UUID>(SubobjectKey::compareUuid);
        authorized.addAll(previous.authorizedUserIds());

        var shUpserts = new LinkedHashMap<UUID, PotVersion.Shareholder>();
        int addedShareholders = composeShareholders(
                potId, prevShareholders, delta, attached, authorized, changedKeys, shUpserts);

        var exUpserts = new LinkedHashMap<UUID, PotVersion.Expense>();
        int addedExpenses = composeExpenses(potId, prevExpenses, delta, changedKeys, exUpserts);

        changedKeys.sort(Comparator.naturalOrder());
        PotVersion contentShell = new PotVersion(
                potId,
                targetVersion,
                headerVersion,
                detailsVersion,
                deleted,
                List.copyOf(shUpserts.values()),
                List.copyOf(exUpserts.values()),
                authorized,
                attached);

        return Optional.of(
                new ComposedDelta(
                        headerVersion,
                        detailsVersion,
                        deleted,
                        previous.shareholdersCount() + addedShareholders,
                        previous.expensesCount() + addedExpenses,
                        Collections.unmodifiableSet(authorized),
                        Collections.unmodifiableSet(attached),
                        Collections.unmodifiableMap(shUpserts),
                        Collections.unmodifiableMap(exUpserts),
                        List.copyOf(changedKeys),
                        contentShell));
    }

    /**
     * D2: Any header or details change on a shareholder that was already attached and active in P_K
     * can remove or replace a user_id in attachedActiveUserIds / authorizedUserIds; delegate to full.
     */
    private static boolean hasGlobalShareholderEffect(
            Map<UUID, PotVersion.Shareholder> prevShareholders, StateDelta delta) {
        if (delta.shareholderHeaders().isEmpty() && delta.shareholderDetails().isEmpty()) {
            return false;
        }
        for (var s : prevShareholders.values()) {
            if (s.deleted() || s.isFree()) continue;
            if (delta.shareholderHeaders().containsKey(s.id())
                    || delta.shareholderDetails().containsKey(s.id())) {
                return true;
            }
        }
        return false;
    }

    private static int composeShareholders(
            UUID potId,
            Map<UUID, PotVersion.Shareholder> prevShareholders,
            StateDelta delta,
            Set<UUID> attached,
            Set<UUID> authorized,
            List<SubobjectKey> changedKeys,
            Map<UUID, PotVersion.Shareholder> upserts) {
        var affectedIds = affectedShareholderIds(delta);
        if (affectedIds.isEmpty()) return 0;

        int added = 0;
        for (var id : affectedIds) {
            var existing = prevShareholders.get(id);
            var h = delta.shareholderHeaders().get(id);
            var d = delta.shareholderDetails().get(id);
            var w = delta.shareholderWeights().get(id);
            if (existing != null) {
                long hv = existing.headerVersion();
                boolean deleted = existing.deleted();
                if (h != null) {
                    requireMonotone("shareholder_header", hv, h.localVersion());
                    hv = h.localVersion();
                    deleted = h.deleted();
                    changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_HEADER, id, hv));
                }
                long dv = existing.detailsVersion();
                boolean isFree = existing.isFree();
                if (d != null) {
                    requireMonotone("shareholder_details", dv, d.localVersion());
                    dv = d.localVersion();
                    isFree = d.userId() == null;
                    changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_DETAILS, id, dv));
                    if (!deleted && d.userId() != null) {
                        attached.add(d.userId());
                        authorized.add(d.userId());
                    }
                }
                long wv = existing.weightVersion();
                if (w != null) {
                    requireMonotone("shareholder_weight", wv, w.localVersion());
                    wv = w.localVersion();
                    changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_WEIGHT, id, wv));
                }
                upserts.put(id, new PotVersion.Shareholder(id, hv, dv, wv, deleted, isFree));
            } else {
                if (h == null || d == null || w == null) {
                    throw new InconsistentPotFactsException("Incomplete new shareholder delta: " + id);
                }
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_HEADER, id, h.localVersion()));
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_DETAILS, id, d.localVersion()));
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_WEIGHT, id, w.localVersion()));
                if (!h.deleted() && d.userId() != null) {
                    attached.add(d.userId());
                    authorized.add(d.userId());
                }
                upserts.put(
                        id,
                        new PotVersion.Shareholder(
                                id,
                                h.localVersion(),
                                d.localVersion(),
                                w.localVersion(),
                                h.deleted(),
                                d.userId() == null));
                added++;
            }
        }
        return added;
    }

    private static int composeExpenses(
            UUID potId,
            Map<UUID, PotVersion.Expense> prevExpenses,
            StateDelta delta,
            List<SubobjectKey> changedKeys,
            Map<UUID, PotVersion.Expense> upserts) {
        var affectedIds = affectedExpenseIds(delta);
        if (affectedIds.isEmpty()) return 0;

        int added = 0;
        for (var id : affectedIds) {
            var existing = prevExpenses.get(id);
            var h = delta.expenseHeaders().get(id);
            var d = delta.expenseDetails().get(id);
            var f = delta.expenseFinancials().get(id);
            if (existing != null) {
                long hv = existing.headerVersion();
                boolean deleted = existing.deleted();
                if (h != null) {
                    requireMonotone("expense_header", hv, h.localVersion());
                    hv = h.localVersion();
                    deleted = h.deleted();
                    changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_HEADER, id, hv));
                }
                long dv = existing.detailsVersion();
                if (d != null) {
                    requireMonotone("expense_details", dv, d.localVersion());
                    dv = d.localVersion();
                    changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_DETAILS, id, dv));
                }
                long fv = existing.financialsVersion();
                if (f != null) {
                    requireMonotone("expense_financials", fv, f.localVersion());
                    fv = f.localVersion();
                    changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_FINANCIALS, id, fv));
                }
                upserts.put(id, new PotVersion.Expense(id, hv, dv, fv, deleted));
            } else {
                if (h == null || d == null || f == null) {
                    throw new InconsistentPotFactsException("Incomplete new expense delta: " + id);
                }
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_HEADER, id, h.localVersion()));
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_DETAILS, id, d.localVersion()));
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_FINANCIALS, id, f.localVersion()));
                upserts.put(
                        id,
                        new PotVersion.Expense(
                                id, h.localVersion(), d.localVersion(), f.localVersion(), h.deleted()));
                added++;
            }
        }
        return added;
    }

    private static void requireMonotone(String component, long previousVersion, long nextVersion) {
        if (nextVersion <= previousVersion) {
            throw new InconsistentPotFactsException(
                    "Non-monotone local version in "
                            + component
                            + ": "
                            + previousVersion
                            + " -> "
                            + nextVersion);
        }
    }
}
