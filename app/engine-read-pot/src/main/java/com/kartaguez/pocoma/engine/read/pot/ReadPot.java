package com.kartaguez.pocoma.engine.read.pot;
import com.kartaguez.pocoma.contract.readquery.ReadLimits;
import com.kartaguez.pocoma.contract.readquery.ReadPotResult;
import com.kartaguez.pocoma.contract.readquery.PotRead;
import com.kartaguez.pocoma.contract.readquery.PotRead.*;

import static com.kartaguez.pocoma.domain.projection.SubobjectContent.*;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.*;
import static com.kartaguez.pocoma.contract.readquery.ReadPotResult.*;

import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.ExternalUserIdentity;
import com.kartaguez.pocoma.port.projection.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.util.*;

/** READ-only queries. The composition supplies one caller-owned RR snapshot per invocation. */
public final class ReadPot {
    /** Detached query boundary; composition owns snapshot, authority and quota. */


    private final PotVersionStore pots;
    private final SubobjectVersionStore objects;
    private final PotBalanceStore balances;
    private final BindingReader bindings;
    private final ReadLimits limits;

    public ReadPot(
            PotVersionStore pots,
            SubobjectVersionStore objects,
            PotBalanceStore balances,
            BindingReader bindings,
            ReadLimits limits) {
        this.pots = Objects.requireNonNull(pots);
        this.objects = Objects.requireNonNull(objects);
        this.balances = Objects.requireNonNull(balances);
        this.bindings = Objects.requireNonNull(bindings);
        this.limits = Objects.requireNonNull(limits);
    }

    public ReadPotResult<Pot> current(
            ExternalIdentity eui, String token, UUID pot, long minVersion, RecoveryReadContext c) {
        if (!input(eui, token, pot) || minVersion < 0) return new InvalidInput<>();
        var user = user(eui, token, c);
        if (user.isEmpty()) return new NotFound<>();
        var current = pots.currentVersion(pot, c);
        if (current.isEmpty()) return new NotFound<>();
        return main(pot, current.getAsLong(), minVersion, user.get(), true, c);
    }

    public ReadPotResult<Pot> at(
            ExternalIdentity eui, String token, UUID pot, long version, RecoveryReadContext c) {
        if (!input(eui, token, pot) || version < 1) return new InvalidInput<>();
        var user = user(eui, token, c);
        if (user.isEmpty()) return new NotFound<>();
        return main(pot, version, 0, user.get(), false, c);
    }

    private ReadPotResult<Pot> main(
            UUID pot,
            long version,
            long minimum,
            UUID user,
            boolean currentPointer,
            RecoveryReadContext c) {
        var root = pots.read(pot, version, c);
        if (currentPointer && root.isEmpty()) throw new IllegalStateException("orphan CurrentPot");
        var state = root.filter(v -> !v.deleted() && v.authorizedUserIds().contains(user));
        if (state.isEmpty()) return new NotFound<>();
        var p = state.get();
        if (version < minimum) return new NotReady<>();
        var keys = collectMainViewKeys(pot, p);
        var values = objects.readAll(keys, c);
        var balance = balances.read(pot, version, c);
        if (!values.keySet().containsAll(keys) || balance.isEmpty()) return new NotReady<>();
        verifyMainViewHeaderAndBalance(pot, version, p, values.get(keys.get(0)), balance.get());
        var details = (PotDetailsContent) values.get(keys.get(1)).content();
        var shareholders = buildShareholderViews(pot, p, values);
        verifyBalanceKeys(balance.get(), p);
        var view =
                new Pot(
                        pot,
                        details.value().label().value(),
                        p.headerVersion(),
                        p.detailsVersion(),
                        shareholders,
                        balance.get().values(),
                        p.expenses().stream().map(ReadPot::ref).toList());
        return fits(view) ? new Available<>(view, version) : new Unavailable<>();
    }

    private static void verifyMainViewHeaderAndBalance(
            UUID pot,
            long version,
            PotVersion p,
            SubobjectVersion headerObj,
            PotBalance balance) {
        if (!balance.potId().equals(pot) || balance.version() != version) {
            throw new IllegalStateException("foreign Balance");
        }
        var header = (PotHeaderContent) headerObj.content();
        if (header.value().deleted() != p.deleted()) {
            throw new IllegalStateException("inconsistent Pot header");
        }
    }

    private static List<SubobjectKey> collectMainViewKeys(UUID pot, PotVersion p) {
        var keys = new ArrayList<SubobjectKey>();
        keys.add(key(pot, POT_HEADER, pot, p.headerVersion()));
        keys.add(key(pot, POT_DETAILS, pot, p.detailsVersion()));
        for (var s : p.shareholders()) {
            keys.add(key(pot, SHAREHOLDER_HEADER, s.id(), s.headerVersion()));
            keys.add(key(pot, SHAREHOLDER_DETAILS, s.id(), s.detailsVersion()));
            keys.add(key(pot, SHAREHOLDER_WEIGHT, s.id(), s.weightVersion()));
        }
        return keys;
    }

    private static List<Shareholder> buildShareholderViews(
            UUID pot, PotVersion p, Map<SubobjectKey, SubobjectVersion> values) {
        var shareholders = new ArrayList<Shareholder>();
        for (var s : p.shareholders()) {
            var h =
                    (ShareholderHeaderContent)
                            values.get(key(pot, SHAREHOLDER_HEADER, s.id(), s.headerVersion()))
                                    .content();
            var d =
                    (ShareholderDetailsContent)
                            values.get(key(pot, SHAREHOLDER_DETAILS, s.id(), s.detailsVersion()))
                                    .content();
            var w =
                    (ShareholderWeightContent)
                            values.get(key(pot, SHAREHOLDER_WEIGHT, s.id(), s.weightVersion()))
                                    .content();
            if (h.value().deleted() != s.deleted() || d.value().userId().isEmpty() != s.isFree()) {
                throw new IllegalStateException("inconsistent Shareholder");
            }
            shareholders.add(
                    new Shareholder(
                            s.id(),
                            s.headerVersion(),
                            s.detailsVersion(),
                            s.weightVersion(),
                            s.deleted(),
                            s.isFree(),
                            d.value().name().value(),
                            w.value().defaultWeight().value()));
        }
        return shareholders;
    }

    private static void verifyBalanceKeys(PotBalance balance, PotVersion state) {
        var expected = new HashSet<UUID>();
        state.shareholders().forEach(s -> expected.add(s.id()));
        if (!balance.values().keySet().equals(expected)) {
            throw new IllegalStateException("incomplete Balance vector");
        }
    }

    public ReadPotResult<PotBalance> balance(
            ExternalIdentity eui, String token, UUID pot, long version, RecoveryReadContext c) {
        if (!input(eui, token, pot) || version < 1) return new InvalidInput<>();
        var user = user(eui, token, c);
        if (user.isEmpty()) return new NotFound<>();
        var state = authorized(pot, version, user.get(), c);
        if (state.isEmpty()) return new NotFound<>();
        var value = balances.read(pot, version, c);
        if (value.isEmpty()) return new NotReady<>();
        verifyBalanceKeys(value.get(), state.get());
        return fits(value.get()) ? new Available<>(value.get(), version) : new Unavailable<>();
    }

    public ReadPotResult<List<Expense>> expenses(
            ExternalIdentity eui,
            String token,
            UUID pot,
            long version,
            List<ExpenseRef> requested,
            RecoveryReadContext c) {
        if (!input(eui, token, pot) || version < 1 || !limits.validBatch(requested)) {
            return new InvalidInput<>();
        }
        requested = List.copyOf(requested);
        var state = loadAuthorizedVersion(eui, token, pot, version, c);
        if (state.isEmpty()) return new NotFound<>();
        var manifest = new HashSet<>(state.get().expenses().stream().map(ReadPot::ref).toList());
        if (!manifest.containsAll(requested)) return new NotFound<>();
        var keys = collectExpenseKeys(pot, requested);
        var values = objects.readAll(keys, c);
        if (!values.keySet().containsAll(keys)) return new NotReady<>();
        var view = requested.stream().map(e -> buildExpenseView(pot, e, values)).toList();
        return fits(view) ? new Available<>(view, version) : new Unavailable<>();
    }

    private Optional<PotVersion> loadAuthorizedVersion(
            ExternalIdentity eui, String token, UUID pot, long version, RecoveryReadContext c) {
        var user = user(eui, token, c);
        if (user.isEmpty()) return Optional.empty();
        return authorized(pot, version, user.get(), c);
    }

    private static List<SubobjectKey> collectExpenseKeys(UUID pot, List<ExpenseRef> requested) {
        var keys = new ArrayList<SubobjectKey>();
        for (var e : requested) {
            keys.add(key(pot, EXPENSE_HEADER, e.id(), e.headerVersion()));
            keys.add(key(pot, EXPENSE_DETAILS, e.id(), e.detailsVersion()));
            keys.add(key(pot, EXPENSE_FINANCIALS, e.id(), e.financialsVersion()));
        }
        return keys;
    }

    private static Expense buildExpenseView(
            UUID pot, ExpenseRef e, Map<SubobjectKey, SubobjectVersion> values) {
        var h =
                (ExpenseHeaderContent)
                        values.get(key(pot, EXPENSE_HEADER, e.id(), e.headerVersion())).content();
        var d =
                ((ExpenseDetailsContent)
                                values.get(key(pot, EXPENSE_DETAILS, e.id(), e.detailsVersion()))
                                        .content())
                        .value();
        var f =
                ((ExpenseFinancialsContent)
                                values.get(
                                                key(
                                                        pot,
                                                        EXPENSE_FINANCIALS,
                                                        e.id(),
                                                        e.financialsVersion()))
                                        .content())
                        .value();
        if (h.value().deleted() != e.deleted()) {
            throw new IllegalStateException("inconsistent Expense");
        }
        var participants =
                f.participants().entrySet().stream()
                        .sorted(
                                (a, b) ->
                                        SubobjectKey.compareUuid(
                                                a.getKey().value(), b.getKey().value()))
                        .map(
                                v ->
                                        new Participation(
                                                v.getKey().value(), v.getValue().map(w -> w.value())))
                        .toList();
        return new Expense(
                e,
                d.label().value(),
                d.date(),
                f.amount().value(),
                f.payerId().value(),
                participants);
    }

    Optional<UUID> user(ExternalIdentity eui, String token, RecoveryReadContext c) {
        var b = bindings.read(eui, c);
        if (b.isPresent() && !b.get().identity().equals(eui))
            throw new IllegalStateException("foreign Binding");
        return b.filter(ExternalUserIdentity::isBound)
                .filter(v -> v.bindingToken().value().equals(token))
                .map(v -> v.userId().value());
    }

    private Optional<PotVersion> authorized(
            UUID pot, long version, UUID user, RecoveryReadContext c) {
        return pots.read(pot, version, c)
                .filter(v -> !v.deleted() && v.authorizedUserIds().contains(user));
    }

    static boolean input(ExternalIdentity eui, String token, UUID pot) {
        return eui != null && token != null && !token.isBlank() && pot != null;
    }

    private static SubobjectKey key(UUID p, SubobjectKind k, UUID id, long v) {
        return new SubobjectKey(p, k, id, v);
    }

    private static ExpenseRef ref(PotVersion.Expense e) {
        return new ExpenseRef(
                e.id(), e.headerVersion(), e.detailsVersion(), e.financialsVersion(), e.deleted());
    }

    /**
     * Conservative textual budget without constructing an unbounded serialized copy. L5 owns HTTP
     * encoding.
     */
    private boolean fits(Object value) {
        var budget = new Budget(limits.responseBudget());
        if (value instanceof Pot p) {
            budgetPot(budget, p);
        } else if (value instanceof PotBalance b) {
            budget.take(128);
            b.values()
                    .forEach(
                            (id, f) -> {
                                budget.take(80);
                                budget.fraction(f);
                            });
        } else if (value instanceof List<?> expenses)
            for (var item : expenses) {
                var e = (Expense) item;
                budget.take(512);
                budget.text(e.label());
                budget.fraction(e.amount());
                for (var participant : e.participants()) {
                    budget.take(80);
                    participant.specificWeight().ifPresent(budget::fraction);
                }
            }
        return budget.available;
    }

    private static void budgetPot(Budget budget, Pot p) {
        budget.take(256);
        budget.text(p.label());
        for (var shareholder : p.shareholders()) {
            budget.take(256);
            budget.text(shareholder.name());
            budget.fraction(shareholder.defaultWeight());
        }
        p.balances()
                .forEach(
                        (id, f) -> {
                            budget.take(80);
                            budget.fraction(f);
                        });
        budget.take(256L * p.expenses().size());
    }

    private static final class Budget {
        long remaining;
        boolean available = true;

        Budget(long remaining) {
            this.remaining = remaining;
        }

        void take(long amount) {
            if (amount > remaining) available = false;
            else remaining -= amount;
        }

        // Six bytes per UTF-16 code unit also bounds JSON escaping and UTF-8 expansion.
        void text(String text) {
            take(6L * text.length());
        }

        void fraction(com.kartaguez.pocoma.domain.pot.Fraction f) {
            take(32);
            text(f.numerator().toString());
            text(f.denominator().toString());
        }
    }
}
