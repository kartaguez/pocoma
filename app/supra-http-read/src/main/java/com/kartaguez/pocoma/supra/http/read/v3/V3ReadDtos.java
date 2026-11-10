package com.kartaguez.pocoma.supra.http.read.v3;

import com.kartaguez.pocoma.domain.pot.v3.Fraction;
import com.kartaguez.pocoma.contract.readquery.ReadPotV3Result;
import java.util.*;

/** Explicit public allowlists; no authority, User ID or persistence record is serialized. */
public final class V3ReadDtos {
    private V3ReadDtos() {}
    public record Rational(String n, String d) {}
    public record Shareholder(UUID id, long headerVersion, long detailsVersion, long weightVersion,
            boolean deleted, boolean isFree, String name, Rational defaultWeight) {}
    public record Pot(long potVersion, UUID id, String label, long headerVersion, long detailsVersion,
            List<Shareholder> shareholders, Map<UUID, Rational> balances, List<ReadPotV3Result.ExpenseRef> expenses) {}
    public record Balance(UUID potId, long potVersion, Map<UUID, Rational> balances) {}
    public record Participant(UUID shareholderId, Rational specificWeight) {}
    public record Expense(ReadPotV3Result.ExpenseRef reference, String label, String date,
            Rational amount, UUID payerId, List<Participant> participants) {}
    public record Batch(UUID potId, long potVersion, List<Expense> expenses) {}
    public record Navigation(List<UUID> potIds, String nextCursor) {}
    public record Binding(long euiVersion, boolean active, String bindingToken) {
        @Override public String toString() { return "Binding[REDACTED]"; }
    }
    public record Command(UUID requestId, String operation, UUID potId, String outcome, Long resultingPotVersion, String rejection) {}
    public record Eui(String issuer, String subject) {}
    public record Identity(UUID requestId, String operation, String outcome, Eui targetEui, Long targetVersion, String rejection) {}
    public static Rational rational(Fraction value) { return new Rational(value.numerator().toString(), value.denominator().toString()); }
    public static Map<UUID, Rational> balances(Map<UUID, Fraction> source) {
        var result = new LinkedHashMap<UUID, Rational>(); source.forEach((key, value) -> result.put(key, rational(value))); return result;
    }
    public static Pot pot(ReadPotV3Result.Pot value, long version) {
        return new Pot(version, value.id(), value.label(), value.headerVersion(), value.detailsVersion(),
            value.shareholders().stream().map(s -> new Shareholder(s.id(), s.headerVersion(), s.detailsVersion(), s.weightVersion(), s.deleted(), s.isFree(), s.name(), rational(s.defaultWeight()))).toList(), balances(value.balances()), value.expenses());
    }
    public static Batch batch(UUID pot, long version, List<ReadPotV3Result.Expense> source) {
        return new Batch(pot, version, source.stream().map(e -> new Expense(e.reference(), e.label(), e.date().toString(), rational(e.amount()), e.payerId(),
            e.participants().stream().map(p -> new Participant(p.shareholderId(), p.specificWeight().map(V3ReadDtos::rational).orElse(null))).toList())).toList());
    }
}
