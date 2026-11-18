package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.domain.pot.revision.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.SubobjectKind;
import com.kartaguez.pocoma.engine.materialize.pot.PotVersionSource.StateDelta;
import java.util.*;

/** Pure state composition P_N = P_K (+) Delta_{K -> N} with global-effect detection (D2). */
final class PotVersionDeltaComposer {
    private PotVersionDeltaComposer() {}

    record Composed(PotVersion value, List<SubobjectKey> changedKeys) {}

    static Optional<Composed> compose(PotVersion previous, long targetVersion, StateDelta delta) {
        if (hasGlobalShareholderEffect(previous, delta)) {
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

        var attached = new HashSet<>(previous.attachedActiveUserIds());
        var authorized = new HashSet<>(previous.authorizedUserIds());
        var shareholders = composeShareholders(potId, previous.shareholders(), delta, attached, authorized, changedKeys);
        var expenses = composeExpenses(potId, previous.expenses(), delta, changedKeys);

        changedKeys.sort(Comparator.naturalOrder());
        var composed =
                new PotVersion(
                        potId,
                        targetVersion,
                        headerVersion,
                        detailsVersion,
                        deleted,
                        shareholders,
                        expenses,
                        authorized,
                        attached);
        return Optional.of(new Composed(composed, List.copyOf(changedKeys)));
    }

    /**
     * D2: Any header or details change on a shareholder that was already attached and active in P_K
     * can remove or replace a user_id in attachedActiveUserIds / authorizedUserIds; delegate to full.
     */
    private static boolean hasGlobalShareholderEffect(PotVersion previous, StateDelta delta) {
        if (delta.shareholderHeaders().isEmpty() && delta.shareholderDetails().isEmpty()) {
            return false;
        }
        for (var s : previous.shareholders()) {
            if (s.deleted() || s.isFree()) continue;
            if (delta.shareholderHeaders().containsKey(s.id())
                    || delta.shareholderDetails().containsKey(s.id())) {
                return true;
            }
        }
        return false;
    }

    private static List<PotVersion.Shareholder> composeShareholders(
            UUID potId,
            List<PotVersion.Shareholder> previous,
            StateDelta delta,
            Set<UUID> attached,
            Set<UUID> authorized,
            List<SubobjectKey> changedKeys) {
        var affectedIds = new HashSet<UUID>();
        affectedIds.addAll(delta.shareholderHeaders().keySet());
        affectedIds.addAll(delta.shareholderDetails().keySet());
        affectedIds.addAll(delta.shareholderWeights().keySet());
        if (affectedIds.isEmpty()) return previous;

        var result = new ArrayList<PotVersion.Shareholder>(previous.size() + affectedIds.size());
        var existingIds = new HashSet<UUID>(previous.size() * 2);
        for (var s : previous) {
            existingIds.add(s.id());
            if (!affectedIds.contains(s.id())) {
                result.add(s);
                continue;
            }
            var h = delta.shareholderHeaders().get(s.id());
            var d = delta.shareholderDetails().get(s.id());
            var w = delta.shareholderWeights().get(s.id());
            long hv = s.headerVersion();
            boolean deleted = s.deleted();
            if (h != null) {
                requireMonotone("shareholder_header", hv, h.localVersion());
                hv = h.localVersion();
                deleted = h.deleted();
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_HEADER, s.id(), hv));
            }
            long dv = s.detailsVersion();
            boolean isFree = s.isFree();
            if (d != null) {
                requireMonotone("shareholder_details", dv, d.localVersion());
                dv = d.localVersion();
                isFree = d.userId() == null;
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_DETAILS, s.id(), dv));
                if (!deleted && d.userId() != null) {
                    attached.add(d.userId());
                    authorized.add(d.userId());
                }
            }
            long wv = s.weightVersion();
            if (w != null) {
                requireMonotone("shareholder_weight", wv, w.localVersion());
                wv = w.localVersion();
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.SHAREHOLDER_WEIGHT, s.id(), wv));
            }
            result.add(new PotVersion.Shareholder(s.id(), hv, dv, wv, deleted, isFree));
        }

        for (var id : affectedIds) {
            if (existingIds.contains(id)) continue;
            var h = delta.shareholderHeaders().get(id);
            var d = delta.shareholderDetails().get(id);
            var w = delta.shareholderWeights().get(id);
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
            result.add(
                    new PotVersion.Shareholder(
                            id,
                            h.localVersion(),
                            d.localVersion(),
                            w.localVersion(),
                            h.deleted(),
                            d.userId() == null));
        }
        return result;
    }

    private static List<PotVersion.Expense> composeExpenses(
            UUID potId,
            List<PotVersion.Expense> previous,
            StateDelta delta,
            List<SubobjectKey> changedKeys) {
        var affectedIds = new HashSet<UUID>();
        affectedIds.addAll(delta.expenseHeaders().keySet());
        affectedIds.addAll(delta.expenseDetails().keySet());
        affectedIds.addAll(delta.expenseFinancials().keySet());
        if (affectedIds.isEmpty()) return previous;

        var result = new ArrayList<PotVersion.Expense>(previous.size() + affectedIds.size());
        var existingIds = new HashSet<UUID>(previous.size() * 2);
        for (var e : previous) {
            existingIds.add(e.id());
            if (!affectedIds.contains(e.id())) {
                result.add(e);
                continue;
            }
            var h = delta.expenseHeaders().get(e.id());
            var d = delta.expenseDetails().get(e.id());
            var f = delta.expenseFinancials().get(e.id());
            long hv = e.headerVersion();
            boolean deleted = e.deleted();
            if (h != null) {
                requireMonotone("expense_header", hv, h.localVersion());
                hv = h.localVersion();
                deleted = h.deleted();
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_HEADER, e.id(), hv));
            }
            long dv = e.detailsVersion();
            if (d != null) {
                requireMonotone("expense_details", dv, d.localVersion());
                dv = d.localVersion();
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_DETAILS, e.id(), dv));
            }
            long fv = e.financialsVersion();
            if (f != null) {
                requireMonotone("expense_financials", fv, f.localVersion());
                fv = f.localVersion();
                changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_FINANCIALS, e.id(), fv));
            }
            result.add(new PotVersion.Expense(e.id(), hv, dv, fv, deleted));
        }

        for (var id : affectedIds) {
            if (existingIds.contains(id)) continue;
            var h = delta.expenseHeaders().get(id);
            var d = delta.expenseDetails().get(id);
            var f = delta.expenseFinancials().get(id);
            if (h == null || d == null || f == null) {
                throw new InconsistentPotFactsException("Incomplete new expense delta: " + id);
            }
            changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_HEADER, id, h.localVersion()));
            changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_DETAILS, id, d.localVersion()));
            changedKeys.add(new SubobjectKey(potId, SubobjectKind.EXPENSE_FINANCIALS, id, f.localVersion()));
            result.add(
                    new PotVersion.Expense(
                            id, h.localVersion(), d.localVersion(), f.localVersion(), h.deleted()));
        }
        return result;
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
