package com.kartaguez.pocoma.infra.postgres.write.pot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.kartaguez.pocoma.domain.pot.*;
import com.kartaguez.pocoma.domain.pot.expense.*;
import com.kartaguez.pocoma.domain.pot.invitation.*;
import com.kartaguez.pocoma.domain.pot.revision.*;
import com.kartaguez.pocoma.domain.pot.shareholder.*;
import com.kartaguez.pocoma.domain.pot.value.*;
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
        return encodeNode(intent).toString();
    }

    private static ObjectNode encodeNode(Intent intent) {
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
            case DeleteShareholder i -> n.put("id", i.id().toString());
            case AddShareholders i -> encodeAddShareholders(n, i);
            case UpdateShareholderDetails i -> encodeUpdateShareholderDetails(n, i);
            case UpdateShareholderWeights i -> encodeUpdateShareholderWeights(n, i);
            case DeleteExpense i -> n.put("id", i.id().toString());
            case CreateExpense i -> encodeCreateExpense(n, i);
            case UpdateExpenseDetails i -> encodeUpdateExpenseDetails(n, i);
            case UpdateExpenseFinancials i -> n.set("financials", financials(i.financials()));
        }
        return n;
    }

    private static void encodeAddShareholders(ObjectNode n, AddShareholders i) {
        var targets = n.withArray("targets");
        if (i.serverAssigned()) {
            n.put("payloadVersion", 3);
            i.creations()
                    .forEach(
                            t -> {
                                var target =
                                        targets.addObject()
                                                .put("name", t.name().value())
                                                .put("attachment", t.attachment().name());
                                target.set("weight", fraction(t.defaultWeight().value()));
                            });
            return;
        }
        i.targets()
                .forEach(
                        t -> {
                            var target =
                                    targets.addObject()
                                            .put("id", t.id().toString())
                                            .put("name", t.name().value());
                            target.put(
                                    "userId",
                                    t.userId().map(u -> u.value().toString()).orElse(null));
                            target.set("weight", fraction(t.defaultWeight().value()));
                        });
    }

    private static void encodeUpdateShareholderDetails(
            ObjectNode n, UpdateShareholderDetails i) {
        var targets = n.withArray("targets");
        i.targets()
                .forEach(
                        t ->
                                targets.addObject()
                                        .put("id", t.id().toString())
                                        .put("name", t.name().value())
                                        .put(
                                                "userId",
                                                t.userId()
                                                        .map(u -> u.value().toString())
                                                        .orElse(null)));
    }

    private static void encodeUpdateShareholderWeights(
            ObjectNode n, UpdateShareholderWeights i) {
        var targets = n.withArray("targets");
        i.targets()
                .forEach(
                        t ->
                                targets.addObject()
                                        .put("id", t.id().toString())
                                        .set("weight", fraction(t.weight().value())));
    }

    private static void encodeCreateExpense(ObjectNode n, CreateExpense i) {
        if (i.serverAssigned()) {
            n.put("payloadVersion", 3);
            n.put("label", i.label().value());
            n.put("date", i.date().toString());
            n.put("payerId", i.payerId().toString());
            n.set("amount", fraction(i.amount().value()));
            encodeParticipants(n, i.participants());
            return;
        }
        // Historical v1 format: preserve exact historical shape using legacyPotId.
        encodeCreateExpenseLegacy(n, i.legacyPotId(), i);
    }

    private static void encodeCreateExpenseLegacy(ObjectNode n, PotId potId, CreateExpense i) {
        n.set("details", details(i.details(potId, i.legacyId())));
        n.set("financials", financials(i.financials(potId, i.legacyId())));
    }

    private static void encodeUpdateExpenseDetails(ObjectNode n, UpdateExpenseDetails i) {
        n.put("id", i.id().toString());
        n.put("label", i.label().value());
        n.put("date", i.date().toString());
    }

    /**
     * New envelopes are deliberately unreadable by old workers. Upgrade consumers before
     * activation.
     */
    public static String encode(PotCommand command) {
        if (command.intent() instanceof CreateExpense e && !e.serverAssigned()) {
            var n = JSON.createObjectNode();
            encodeCreateExpenseLegacy(n, command.potId(), e);
            return n.toString();
        }
        if (command.attachments().isEmpty()) return encode(command.intent());
        var n = JSON.createObjectNode().put("payloadVersion", 2);
        n.set("intention", encodeNode(command.intent()));
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
                var version = n.required("payloadVersion");
                if (!isIntegerNode(version)) throw new IllegalArgumentException();
                int v = version.intValue();
                if (v == 2) {
                    modes.putAll(decodeAttachmentsV2(n));
                    payload = n.required("intention").toString();
                    return new PotCommand(
                            id, eui, token, pot, observed, decode(operation, payload), modes);
                }
                if (v == 3) {
                    return new PotCommand(
                            id, eui, token, pot, observed, decodeV3(operation, n), modes);
                }
                throw new IllegalArgumentException();
            }
            return new PotCommand(id, eui, token, pot, observed, decode(operation, payload), modes);
        } catch (Exception invalid) {
            throw new InconsistentPotFactsException("Invalid durable Command envelope");
        }
    }

    private static Intent decodeV3(String operation, JsonNode n) {
        return switch (operation) {
            case "AddShareholders" -> {
                if (n.size() != 2) throw new IllegalArgumentException();
                yield new AddShareholders(list(n, "targets", PotCommandJson::readServerCreation));
            }
            case "CreateExpense" -> {
                if (n.size() != 6) throw new IllegalArgumentException();
                yield new CreateExpense(
                        new Label(text(n, "label")),
                        LocalDate.parse(text(n, "date")),
                        new com.kartaguez.pocoma.domain.pot.value.Amount(
                                readFraction(n.required("amount"))),
                        new ShareholderId(UUID.fromString(text(n, "payerId"))),
                        readParticipants(n));
            }
            default -> throw new IllegalArgumentException("Unsupported payloadVersion 3 operation");
        };
    }

    private static ShareholderCreation readServerCreation(JsonNode t) {
        if (t.size() != 3 || t.has("id") || t.has("userId")) {
            throw new IllegalArgumentException("Invalid v3 shareholder target");
        }
        return ShareholderCreation.of(
                new Name(text(t, "name")),
                Attachment.valueOf(text(t, "attachment")),
                new com.kartaguez.pocoma.domain.pot.value.Weight(
                        readFraction(t.required("weight"))));
    }

    private static Map<ShareholderId, Attachment> decodeAttachmentsV2(JsonNode n) {
        validateEnvelopeV2(n);
        var modes = new HashMap<ShareholderId, Attachment>();
        n.required("attachments")
                .fields()
                .forEachRemaining(
                        e ->
                                modes.put(
                                        new ShareholderId(UUID.fromString(e.getKey())),
                                        Attachment.valueOf(e.getValue().textValue())));
        if (modes.isEmpty()) throw new IllegalArgumentException();
        return modes;
    }

    private static void validateEnvelopeV2(JsonNode n) {
        var version = n.required("payloadVersion");
        if (!isIntegerNode(version) || version.intValue() != 2) {
            throw new IllegalArgumentException();
        }
        if (n.size() != 3 || !n.required("attachments").isObject()) {
            throw new IllegalArgumentException();
        }
    }

    private static boolean isIntegerNode(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt();
    }

    public static Intent decode(String operation, String payload) {
        try {
            var n = JSON.readTree(payload);
            if (n.has("payloadVersion")) {
                throw new IllegalArgumentException("Envelope requires version-aware consumer");
            }
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
                        AddShareholders.legacy(list(n, "targets", PotCommandJson::readCreation));
                case "UpdateShareholderDetails" ->
                        new UpdateShareholderDetails(
                                list(n, "targets", PotCommandJson::readDetailsIntent));
                case "UpdateShareholderWeights" ->
                        new UpdateShareholderWeights(
                                list(n, "targets", PotCommandJson::readWeightIntent));
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

    private static ShareholderTransitions.Creation readCreation(JsonNode t) {
        return new ShareholderTransitions.Creation(
                sid(t),
                new Name(text(t, "name")),
                user(t),
                new com.kartaguez.pocoma.domain.pot.value.Weight(readFraction(t.required("weight"))));
    }

    private static ShareholderTransitions.DetailsIntent readDetailsIntent(JsonNode t) {
        return new ShareholderTransitions.DetailsIntent(sid(t), new Name(text(t, "name")), user(t));
    }

    private static ShareholderTransitions.WeightIntent readWeightIntent(JsonNode t) {
        return new ShareholderTransitions.WeightIntent(
                sid(t),
                new com.kartaguez.pocoma.domain.pot.value.Weight(readFraction(t.required("weight"))));
    }

    public static String encodeCreatedResources(
            com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore.CreatedResources
                    created) {
        if (created == null) return null;
        var n = JSON.createObjectNode();
        if (created.potId() != null) {
            n.put("potId", created.potId().toString());
        } else if (created.expenseId() != null) {
            n.put("expenseId", created.expenseId().toString());
        } else if (created.shareholders() != null) {
            var arr = n.withArray("shareholders");
            for (var s : created.shareholders()) {
                arr.addObject()
                        .put("inputIndex", s.inputIndex())
                        .put("shareholderId", s.shareholderId().toString());
            }
        }
        return n.toString();
    }

    public static com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore.CreatedResources
            decodeCreatedResources(String raw) {
        if (raw == null) return null;
        try {
            var n = JSON.readTree(raw);
            if (!n.isObject() || n.size() != 1) throw new IllegalArgumentException();
            if (n.has("potId")) {
                return com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore
                        .CreatedResources.pot(UUID.fromString(text(n, "potId")));
            }
            if (n.has("expenseId")) {
                return com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore
                        .CreatedResources.expense(UUID.fromString(text(n, "expenseId")));
            }
            if (n.has("shareholders")) {
                var items =
                        list(
                                n,
                                "shareholders",
                                s -> {
                                    if (!s.isObject() || s.size() != 2)
                                        throw new IllegalArgumentException();
                                    var idx = s.required("inputIndex");
                                    if (!isIntegerNode(idx)) throw new IllegalArgumentException();
                                    return new com.kartaguez.pocoma.engine.write.pot.port.execution
                                            .PotCommandStore.CreatedShareholder(
                                            idx.intValue(),
                                            UUID.fromString(text(s, "shareholderId")));
                                });
                return com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore
                        .CreatedResources.shareholders(items);
            }
            throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new InconsistentPotFactsException("Invalid durable CreatedResources");
        }
    }

    private static ObjectNode fraction(com.kartaguez.pocoma.domain.pot.value.Fraction f) {
        return JSON.createObjectNode()
                .put("numerator", f.numerator().toString())
                .put("denominator", f.denominator().toString());
    }

    private static com.kartaguez.pocoma.domain.pot.value.Fraction readFraction(JsonNode n) {
        var value =
                new com.kartaguez.pocoma.domain.pot.value.Fraction(
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
        encodeParticipants(n, f.participants());
        return n;
    }

    private static void encodeParticipants(
            ObjectNode n, Map<ShareholderId, Optional<com.kartaguez.pocoma.domain.pot.value.Weight>> participants) {
        participants.entrySet().stream()
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
    }

    private static List<ExpenseFinancials.Participation> readParticipants(JsonNode n) {
        return list(
                n,
                "participants",
                p ->
                        new ExpenseFinancials.Participation(
                                sid(p),
                                p.required("weight").isNull()
                                        ? Optional.empty()
                                        : Optional.of(
                                                new com.kartaguez.pocoma.domain.pot.value.Weight(
                                                        readFraction(p.required("weight"))))));
    }

    private static ExpenseFinancials readFinancials(JsonNode n) {
        return ExpenseFinancials.of(
                new PotId(UUID.fromString(text(n, "potId"))),
                eid(n),
                new com.kartaguez.pocoma.domain.pot.value.Amount(readFraction(n.required("amount"))),
                new ShareholderId(UUID.fromString(text(n, "payerId"))),
                readParticipants(n));
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
