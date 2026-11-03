package com.kartaguez.pocoma.domain.projection.v3;

import java.util.*;

/** Complete detached composition at one global version; no detailed financial payload. */
public record PotVersion(UUID potId, long version, long headerVersion, long detailsVersion,
                         boolean deleted, List<Shareholder> shareholders, List<Expense> expenses,
                         Set<UUID> authorizedUserIds, Set<UUID> attachedActiveUserIds) {
    public record Shareholder(UUID id, long headerVersion, long detailsVersion, long weightVersion,
                              boolean deleted, boolean isFree) {
        public Shareholder { Objects.requireNonNull(id); positive(headerVersion, detailsVersion, weightVersion); }
    }
    public record Expense(UUID id, long headerVersion, long detailsVersion, long financialsVersion,
                          boolean deleted) {
        public Expense { Objects.requireNonNull(id); positive(headerVersion, detailsVersion, financialsVersion); }
    }
    public PotVersion {
        Objects.requireNonNull(potId); positive(version, headerVersion, detailsVersion);
        shareholders = ordered(shareholders, Shareholder::id);
        expenses = ordered(expenses, Expense::id);
        authorizedUserIds = ids(authorizedUserIds); attachedActiveUserIds = ids(attachedActiveUserIds);
        if (!authorizedUserIds.containsAll(attachedActiveUserIds))
            throw new IllegalArgumentException("Attached users must be authorized");
    }
    public List<SubobjectKey> keys() {
        var keys = new ArrayList<SubobjectKey>();
        keys.add(key(SubobjectKind.POT_HEADER, potId, headerVersion));
        keys.add(key(SubobjectKind.POT_DETAILS, potId, detailsVersion));
        for (var s : shareholders) {
            keys.add(key(SubobjectKind.SHAREHOLDER_HEADER, s.id(), s.headerVersion()));
            keys.add(key(SubobjectKind.SHAREHOLDER_DETAILS, s.id(), s.detailsVersion()));
            keys.add(key(SubobjectKind.SHAREHOLDER_WEIGHT, s.id(), s.weightVersion()));
        }
        for (var e : expenses) {
            keys.add(key(SubobjectKind.EXPENSE_HEADER, e.id(), e.headerVersion()));
            keys.add(key(SubobjectKind.EXPENSE_DETAILS, e.id(), e.detailsVersion()));
            keys.add(key(SubobjectKind.EXPENSE_FINANCIALS, e.id(), e.financialsVersion()));
        }
        keys.sort(Comparator.naturalOrder()); return List.copyOf(keys);
    }
    private SubobjectKey key(SubobjectKind kind, UUID id, long local) { return new SubobjectKey(potId, kind, id, local); }
    private static void positive(long... values) { for (long v : values) if (v < 1) throw new IllegalArgumentException("Positive version required"); }
    private static Set<UUID> ids(Set<UUID> values) {
        var ordered = new TreeSet<UUID>(SubobjectKey::compareUuid); ordered.addAll(values);
        return Collections.unmodifiableSet(ordered);
    }
    private static <T> List<T> ordered(List<T> values, java.util.function.Function<T, UUID> id) {
        var result = new ArrayList<>(values); result.sort((a,b) -> SubobjectKey.compareUuid(id.apply(a), id.apply(b)));
        for (int i=1; i<result.size(); i++) if (id.apply(result.get(i-1)).equals(id.apply(result.get(i))))
            throw new IllegalArgumentException("Duplicate reference");
        return List.copyOf(result);
    }
}
