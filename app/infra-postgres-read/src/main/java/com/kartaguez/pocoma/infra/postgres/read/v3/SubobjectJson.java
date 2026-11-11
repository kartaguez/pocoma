package com.kartaguez.pocoma.infra.postgres.read.v3;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.SubobjectContent.*;
import com.kartaguez.pocoma.port.projection.v3.CorruptSubobjectException;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Explicit schema 1 codec. Persisted values are validated before domain normalization. */
public final class SubobjectJson {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SubobjectJson() {}

    public static String encode(SubobjectVersion version) {
        var n = JSON.createObjectNode();
        switch (version.content()) {
            case PotHeaderContent c -> n.put("deleted", c.value().deleted());
            case PotDetailsContent c -> n.put("label", c.value().label().value())
                    .put("creatorId", c.value().creatorId().value().toString());
            case ShareholderHeaderContent c -> n.put("deleted", c.value().deleted());
            case ShareholderDetailsContent c -> n.put("name", c.value().name().value())
                    .put("userId", c.value().userId().map(u -> u.value().toString()).orElse(null));
            case ShareholderWeightContent c -> n.set("defaultWeight", fraction(c.value().defaultWeight().value()));
            case ExpenseHeaderContent c -> n.put("deleted", c.value().deleted());
            case ExpenseDetailsContent c -> n.put("label", c.value().label().value())
                    .put("date", DateTimeFormatter.ISO_LOCAL_DATE.format(c.value().date()));
            case ExpenseFinancialsContent c -> {
                var f = c.value();
                n.set("amount", fraction(f.amount().value()));
                n.put("payerId", f.payerId().value().toString());
                var entries = n.putArray("participants");
                f.participants().entrySet().stream()
                        .sorted((a, b) -> SubobjectKey.compareUuid(a.getKey().value(), b.getKey().value()))
                        .forEach(e -> {
                            var participant = entries.addObject().put("shareholderId", e.getKey().value().toString());
                            participant.set("specificWeight", e.getValue().map(w -> fraction(w.value())).orElse(null));
                        });
            }
        }
        return n.toString();
    }

    public static SubobjectVersion decode(SubobjectKey key, int schemaVersion, String payload) {
        try {
            if (schemaVersion != 1) throw new IllegalArgumentException();
            var n = JSON.readTree(Objects.requireNonNull(payload));
            var pot = new PotId(key.potId());
            SubobjectContent content = switch (key.kind()) {
                case POT_HEADER -> {
                    fields(n, "deleted");
                    yield new PotHeaderContent(new PotHeader(pot, bool(n, "deleted")));
                }
                case POT_DETAILS -> {
                    fields(n, "label", "creatorId");
                    yield new PotDetailsContent(new PotDetails(pot, new Label(text(n, "label")), new UserId(uuid(n.get("creatorId")))));
                }
                case SHAREHOLDER_HEADER -> {
                    fields(n, "deleted");
                    yield new ShareholderHeaderContent(new ShareholderHeader(pot, new ShareholderId(key.objectId()), bool(n, "deleted")));
                }
                case SHAREHOLDER_DETAILS -> {
                    fields(n, "name", "userId");
                    yield new ShareholderDetailsContent(new ShareholderDetails(pot, new ShareholderId(key.objectId()),
                            new Name(text(n, "name")), n.get("userId").isNull() ? Optional.empty() : Optional.of(new UserId(uuid(n.get("userId"))))));
                }
                case SHAREHOLDER_WEIGHT -> {
                    fields(n, "defaultWeight");
                    yield new ShareholderWeightContent(new ShareholderWeight(pot, new ShareholderId(key.objectId()), new Weight(readFraction(n.get("defaultWeight")))));
                }
                case EXPENSE_HEADER -> {
                    fields(n, "deleted");
                    yield new ExpenseHeaderContent(new ExpenseHeader(pot, new ExpenseId(key.objectId()), bool(n, "deleted")));
                }
                case EXPENSE_DETAILS -> {
                    fields(n, "label", "date");
                    var date = LocalDate.parse(text(n, "date"), DateTimeFormatter.ISO_LOCAL_DATE);
                    if (!DateTimeFormatter.ISO_LOCAL_DATE.format(date).equals(text(n, "date"))) throw new IllegalArgumentException();
                    yield new ExpenseDetailsContent(new ExpenseDetails(pot, new ExpenseId(key.objectId()), new Label(text(n, "label")), date));
                }
                case EXPENSE_FINANCIALS -> {
                    fields(n, "amount", "payerId", "participants");
                    var array = n.get("participants");
                    if (!array.isArray() || array.isEmpty()) throw new IllegalArgumentException();
                    var entries = new ArrayList<ExpenseFinancials.Participation>();
                    UUID previous = null;
                    for (var entry : array) {
                        fields(entry, "shareholderId", "specificWeight");
                        var id = uuid(entry.get("shareholderId"));
                        if (previous != null && SubobjectKey.compareUuid(previous, id) >= 0) throw new IllegalArgumentException();
                        previous = id;
                        entries.add(new ExpenseFinancials.Participation(new ShareholderId(id), entry.get("specificWeight").isNull()
                                ? Optional.empty() : Optional.of(new Weight(readFraction(entry.get("specificWeight"))))));
                    }
                    yield new ExpenseFinancialsContent(ExpenseFinancials.of(pot, new ExpenseId(key.objectId()),
                            new Amount(readFraction(n.get("amount"))), new ShareholderId(uuid(n.get("payerId"))), entries));
                }
            };
            return new SubobjectVersion(key, content);
        } catch (java.io.IOException | RuntimeException invalid) {
            // Parser/domain diagnostics can contain the payload. Expose only the business key.
            throw new CorruptSubobjectException(key);
        }
    }

    private static ObjectNode fraction(Fraction f) {
        return JSON.createObjectNode().put("n", f.numerator().toString()).put("d", f.denominator().toString());
    }

    private static Fraction readFraction(JsonNode node) {
        fields(node, "n", "d");
        String ns = text(node, "n"), ds = text(node, "d");
        if (!ns.matches("0|-?[1-9][0-9]*") || !ds.matches("[1-9][0-9]*")) throw new IllegalArgumentException();
        var n = new BigInteger(ns);
        var d = new BigInteger(ds);
        if (!n.gcd(d).equals(BigInteger.ONE)) throw new IllegalArgumentException();
        return new Fraction(n, d);
    }

    private static void fields(JsonNode n, String... names) {
        if (n == null || !n.isObject() || n.size() != names.length) throw new IllegalArgumentException();
        for (var name : names) if (!n.has(name)) throw new IllegalArgumentException();
    }

    private static String text(JsonNode n, String name) {
        var value = n.get(name);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    private static boolean bool(JsonNode n, String name) {
        if (!n.get(name).isBoolean()) throw new IllegalArgumentException();
        return n.get(name).booleanValue();
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual()) throw new IllegalArgumentException();
        var value = UUID.fromString(node.textValue());
        if (!value.toString().equals(node.textValue())) throw new IllegalArgumentException();
        return value;
    }
}
