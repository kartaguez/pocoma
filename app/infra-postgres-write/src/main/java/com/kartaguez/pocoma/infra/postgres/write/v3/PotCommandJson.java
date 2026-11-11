package com.kartaguez.pocoma.infra.postgres.write.v3;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Explicit durable V3 intention codec; exact integer strings, no polymorphic class names. */
public final class PotCommandJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PotCommandJson() {}

    public static String encode(Intent intent) {
        var n = JSON.createObjectNode();
        switch (intent) {
            case CreateInvitation i -> n.put("expiresAt", i.expiresAt().toString());
            case PotCommand.AttachShareholderViaInvitation i -> {
                n.put("tokenHash", i.tokenHash());
                n.put("shareholderId", i.shareholderId().toString());
            }
            case CreatePot i -> n.put("label", i.label().value());
            case UpdatePotDetails i -> n.put("label", i.label().value());
            case DeletePot i -> {}
            case AddShareholders i ->
                    i.targets()
                            .forEach(
                                    t -> {
                                        var target =
                                                n.withArray("targets")
                                                        .addObject()
                                                        .put("id", t.id().toString())
                                                        .put("name", t.name().value());
                                        target.put(
                                                "userId",
                                                t.userId()
                                                        .map(u -> u.value().toString())
                                                        .orElse(null));
                                        target.set("weight", fraction(t.defaultWeight().value()));
                                    });
            case UpdateShareholderDetails i ->
                    i.targets()
                            .forEach(
                                    t ->
                                            n.withArray("targets")
                                                    .addObject()
                                                    .put("id", t.id().toString())
                                                    .put("name", t.name().value())
                                                    .put(
                                                            "userId",
                                                            t.userId()
                                                                    .map(u -> u.value().toString())
                                                                    .orElse(null)));
            case UpdateShareholderWeights i ->
                    i.targets()
                            .forEach(
                                    t ->
                                            n.withArray("targets")
                                                    .addObject()
                                                    .put("id", t.id().toString())
                                                    .set("weight", fraction(t.weight().value())));
            case DeleteShareholder i -> n.put("id", i.id().toString());
            case CreateExpense i -> {
                n.set("details", details(i.details()));
                n.set("financials", financials(i.financials()));
            }
            case UpdateExpenseDetails i -> {
                n.put("id", i.id().toString());
                n.put("label", i.label().value());
                n.put("date", i.date().toString());
            }
            case UpdateExpenseFinancials i -> n.set("financials", financials(i.financials()));
            case DeleteExpense i -> n.put("id", i.id().toString());
        }
        if (intent instanceof AddShareholders
                || intent instanceof UpdateShareholderDetails
                || intent instanceof UpdateShareholderWeights) n.withArray("targets");
        return n.toString();
    }

    /**
     * New envelopes are deliberately unreadable by old workers. Upgrade consumers before
     * activation.
     */
    public static String encode(PotCommand command) {
        if (command.attachments().isEmpty()) return encode(command.intent());
        var n = JSON.createObjectNode().put("payloadVersion", 2);
        try {
            n.set("intention", JSON.readTree(encode(command.intent())));
        } catch (java.io.IOException failure) {
            throw new InconsistentPotFactsException("Invalid Command encoding");
        }
        var modes = n.putObject("attachments");
        command.attachments().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .forEach(e -> modes.put(e.getKey().toString(), e.getValue().name()));
        return n.toString();
    }

    public static PotCommand decodeCommand(
            UUID id,
            PotCommand.Eui eui,
            String token,
            PotId pot,
            long observed,
            String operation,
            String payload) {
        try {
            var n = JSON.readTree(payload);
            var modes = new HashMap<ShareholderId, Attachment>();
            if (n.has("payloadVersion")) {
                if (!n.required("payloadVersion").isIntegralNumber()
                        || !n.required("payloadVersion").canConvertToInt()
                        || n.required("payloadVersion").intValue() != 2
                        || n.size() != 3
                        || !n.required("attachments").isObject())
                    throw new IllegalArgumentException();
                n.required("attachments")
                        .fields()
                        .forEachRemaining(
                                e ->
                                        modes.put(
                                                new ShareholderId(UUID.fromString(e.getKey())),
                                                Attachment.valueOf(e.getValue().textValue())));
                if (modes.isEmpty()) throw new IllegalArgumentException();
                payload = n.required("intention").toString();
            }
            return new PotCommand(id, eui, token, pot, observed, decode(operation, payload), modes);
        } catch (Exception invalid) {
            throw new InconsistentPotFactsException("Invalid durable Command envelope");
        }
    }

    public static Intent decode(String operation, String payload) {
        try {
            var n = JSON.readTree(payload);
            if (n.has("payloadVersion"))
                throw new IllegalArgumentException("Envelope requires version-aware consumer");
            return switch (operation) {
                case "CreateInvitation" ->
                        new CreateInvitation(java.time.Instant.parse(text(n, "expiresAt")));
                case "AttachShareholderViaInvitation" ->
                        new PotCommand.AttachShareholderViaInvitation(
                                text(n, "tokenHash"),
                                new ShareholderId(UUID.fromString(text(n, "shareholderId"))));
                case "CreatePot" -> new CreatePot(new Label(text(n, "label")));
                case "UpdatePotDetails" -> new UpdatePotDetails(new Label(text(n, "label")));
                case "DeletePot" -> new DeletePot();
                case "AddShareholders" ->
                        new AddShareholders(
                                list(
                                        n,
                                        "targets",
                                        t ->
                                                new ShareholderTransitions.Creation(
                                                        sid(t),
                                                        new Name(text(t, "name")),
                                                        user(t),
                                                        new com.kartaguez.pocoma.domain.pot.v3
                                                                .Weight(
                                                                readFraction(
                                                                        t.required("weight"))))));
                case "UpdateShareholderDetails" ->
                        new UpdateShareholderDetails(
                                list(
                                        n,
                                        "targets",
                                        t ->
                                                new ShareholderTransitions.DetailsIntent(
                                                        sid(t),
                                                        new Name(text(t, "name")),
                                                        user(t))));
                case "UpdateShareholderWeights" ->
                        new UpdateShareholderWeights(
                                list(
                                        n,
                                        "targets",
                                        t ->
                                                new ShareholderTransitions.WeightIntent(
                                                        sid(t),
                                                        new com.kartaguez.pocoma.domain.pot.v3
                                                                .Weight(
                                                                readFraction(
                                                                        t.required("weight"))))));
                case "DeleteShareholder" -> new DeleteShareholder(sid(n));
                case "CreateExpense" ->
                        new CreateExpense(
                                readDetails(n.required("details")),
                                readFinancials(n.required("financials")));
                case "UpdateExpenseDetails" ->
                        new UpdateExpenseDetails(
                                eid(n),
                                new Label(text(n, "label")),
                                LocalDate.parse(text(n, "date")));
                case "UpdateExpenseFinancials" ->
                        new UpdateExpenseFinancials(readFinancials(n.required("financials")));
                case "DeleteExpense" -> new DeleteExpense(eid(n));
                default -> throw new IllegalArgumentException("Unknown durable Pot operation");
            };
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new InconsistentPotFactsException("Invalid durable Command intention");
        }
    }

    private static ObjectNode fraction(com.kartaguez.pocoma.domain.pot.v3.Fraction f) {
        return JSON.createObjectNode()
                .put("numerator", f.numerator().toString())
                .put("denominator", f.denominator().toString());
    }

    private static com.kartaguez.pocoma.domain.pot.v3.Fraction readFraction(JsonNode n) {
        var value =
                new com.kartaguez.pocoma.domain.pot.v3.Fraction(
                        new BigInteger(text(n, "numerator")),
                        new BigInteger(text(n, "denominator")));
        if (!value.numerator().toString().equals(text(n, "numerator"))
                || !value.denominator().toString().equals(text(n, "denominator")))
            throw new IllegalArgumentException("Noncanonical durable rational");
        return value;
    }

    private static ObjectNode details(ExpenseDetails d) {
        return JSON.createObjectNode()
                .put("potId", d.potId().toString())
                .put("id", d.expenseId().toString())
                .put("label", d.label().value())
                .put("date", d.date().toString());
    }

    private static ExpenseDetails readDetails(JsonNode n) {
        return new ExpenseDetails(
                new PotId(UUID.fromString(text(n, "potId"))),
                eid(n),
                new Label(text(n, "label")),
                LocalDate.parse(text(n, "date")));
    }

    private static ObjectNode financials(ExpenseFinancials f) {
        var n =
                JSON.createObjectNode()
                        .put("potId", f.potId().toString())
                        .put("id", f.expenseId().toString())
                        .put("payerId", f.payerId().toString());
        n.set("amount", fraction(f.amount().value()));
        f.participants().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .forEach(
                        e -> {
                            var p =
                                    n.withArray("participants")
                                            .addObject()
                                            .put("id", e.getKey().toString());
                            p.set(
                                    "weight",
                                    e.getValue().map(w -> fraction(w.value())).orElse(null));
                        });
        return n;
    }

    private static ExpenseFinancials readFinancials(JsonNode n) {
        var participants =
                list(
                        n,
                        "participants",
                        p ->
                                new ExpenseFinancials.Participation(
                                        sid(p),
                                        p.required("weight").isNull()
                                                ? Optional.empty()
                                                : Optional.of(
                                                        new com.kartaguez.pocoma.domain.pot.v3
                                                                .Weight(
                                                                readFraction(
                                                                        p.required("weight"))))));
        return ExpenseFinancials.of(
                new PotId(UUID.fromString(text(n, "potId"))),
                eid(n),
                new com.kartaguez.pocoma.domain.pot.v3.Amount(readFraction(n.required("amount"))),
                new ShareholderId(UUID.fromString(text(n, "payerId"))),
                participants);
    }

    private static String text(JsonNode n, String key) {
        var v = n.required(key);
        if (!v.isTextual()) throw new IllegalArgumentException("Text required");
        return v.textValue();
    }

    private static ShareholderId sid(JsonNode n) {
        return new ShareholderId(UUID.fromString(text(n, "id")));
    }

    private static ExpenseId eid(JsonNode n) {
        return new ExpenseId(UUID.fromString(text(n, "id")));
    }

    private static Optional<UserId> user(JsonNode n) {
        return n.required("userId").isNull()
                ? Optional.empty()
                : Optional.of(new UserId(UUID.fromString(text(n, "userId"))));
    }

    private static <T> List<T> list(JsonNode n, String key, Function<JsonNode, T> read) {
        var array = n.required(key);
        if (!array.isArray()) throw new IllegalArgumentException("Array required");
        var result = new ArrayList<T>();
        array.forEach(v -> result.add(read.apply(v)));
        return List.copyOf(result);
    }
}
