package com.kartaguez.pocoma.infra.postgres.read;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketKey;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketValue;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

/**
 * Canonical JSONB codec for {@link PotBalanceAggregate} (schema version 1, D1 integer shareholder
 * indices referencing the canonical UUID-sorted shareholder dictionary).
 */
final class PotBalanceAggregateJson {
    static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PotBalanceAggregateJson() {}

    static String encode(PotBalanceAggregate aggregate) {
        var root = JSON.createObjectNode();
        var shareholders = root.putArray("s");
        for (var entry : aggregate.shareholders()) {
            shareholders
                    .addObject()
                    .put("id", entry.shareholderId().toString())
                    .put("wn", entry.defaultWeight().numerator().toString())
                    .put("wd", entry.defaultWeight().denominator().toString())
                    .put("del", entry.deleted())
                    .put("fn", entry.fixedNet().numerator().toString())
                    .put("fd", entry.fixedNet().denominator().toString());
        }
        var buckets = root.putArray("b");
        for (var entry : aggregate.buckets().entrySet()) {
            BucketKey key = entry.getKey();
            BucketValue val = entry.getValue();
            var node = buckets.addObject();
            var participants = node.putArray("p");
            for (int idx : key.defaultParticipantIndices()) {
                participants.add(idx);
            }
            node.put("swn", key.specificWeightTotal().numerator().toString());
            node.put("swd", key.specificWeightTotal().denominator().toString());
            node.put("an", val.totalAmount().numerator().toString());
            node.put("ad", val.totalAmount().denominator().toString());
            var spec = node.putArray("sq");
            for (var specEntry : val.specWeightedAmounts().entrySet()) {
                spec.addObject()
                        .put("i", specEntry.getKey())
                        .put("n", specEntry.getValue().numerator().toString())
                        .put("d", specEntry.getValue().denominator().toString());
            }
        }
        return root.toString();
    }

    static PotBalanceAggregate decode(
            UUID potId, long version, int schemaVersion, String payload) {
        try {
            if (schemaVersion != SCHEMA_VERSION || payload == null) {
                throw new IllegalArgumentException("Unsupported aggregate schema version");
            }
            JsonNode root = JSON.readTree(payload);
            if (!root.isObject() || root.size() != 2 || !root.has("s") || !root.has("b")) {
                throw new IllegalArgumentException("Invalid aggregate root");
            }
            JsonNode shareholdersNode = root.get("s");
            JsonNode bucketsNode = root.get("b");
            if (!shareholdersNode.isArray() || !bucketsNode.isArray()) {
                throw new IllegalArgumentException("Invalid aggregate arrays");
            }
            var shareholders = new ArrayList<ShareholderEntry>(shareholdersNode.size());
            for (JsonNode shNode : shareholdersNode) {
                shareholders.add(parseShareholder(shNode));
            }
            var buckets = new LinkedHashMap<BucketKey, BucketValue>(bucketsNode.size() * 2);
            BucketKey previousKey = null;
            for (JsonNode bucketNode : bucketsNode) {
                var parsed = parseBucket(bucketNode);
                if (previousKey != null && previousKey.compareTo(parsed.key()) >= 0) {
                    throw new IllegalArgumentException("Buckets are not strictly ordered");
                }
                previousKey = parsed.key();
                buckets.put(parsed.key(), parsed.value());
            }
            return new PotBalanceAggregate(potId, version, shareholders, buckets);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("Corrupt PotBalanceAggregate", invalid);
        }
    }

    private static ShareholderEntry parseShareholder(JsonNode node) {
        if (!node.isObject() || node.size() != 6) {
            throw new IllegalArgumentException("Invalid shareholder entry");
        }
        String idText = requireText(node, "id");
        UUID id = UUID.fromString(idText);
        if (!id.toString().equals(idText)) {
            throw new IllegalArgumentException("Non-canonical shareholder UUID");
        }
        Fraction weight = parseCanonicalFraction(node, "wn", "wd");
        JsonNode delNode = node.get("del");
        if (delNode == null || !delNode.isBoolean()) {
            throw new IllegalArgumentException("Invalid deleted flag");
        }
        Fraction fixedNet = parseCanonicalFraction(node, "fn", "fd");
        return new ShareholderEntry(id, weight, delNode.booleanValue(), fixedNet);
    }

    private record ParsedBucket(BucketKey key, BucketValue value) {}

    private static ParsedBucket parseBucket(JsonNode node) {
        if (!node.isObject() || node.size() != 6) {
            throw new IllegalArgumentException("Invalid bucket entry");
        }
        JsonNode pNode = node.get("p");
        if (pNode == null || !pNode.isArray()) {
            throw new IllegalArgumentException("Invalid bucket participants");
        }
        var defaultIndices = new ArrayList<Integer>(pNode.size());
        for (JsonNode idxNode : pNode) {
            if (!idxNode.isIntegralNumber() || !idxNode.canConvertToInt()) {
                throw new IllegalArgumentException("Invalid participant index");
            }
            defaultIndices.add(idxNode.intValue());
        }
        Fraction specificWeightTotal = parseCanonicalFraction(node, "swn", "swd");
        Fraction totalAmount = parseCanonicalFraction(node, "an", "ad");
        JsonNode sqNode = node.get("sq");
        if (sqNode == null || !sqNode.isArray()) {
            throw new IllegalArgumentException("Invalid bucket specific amounts");
        }
        var specWeighted = new LinkedHashMap<Integer, Fraction>(sqNode.size() * 2);
        int previousSpecIdx = -1;
        for (JsonNode entryNode : sqNode) {
            if (!entryNode.isObject() || entryNode.size() != 3) {
                throw new IllegalArgumentException("Invalid specific amount entry");
            }
            JsonNode iNode = entryNode.get("i");
            if (iNode == null || !iNode.isIntegralNumber() || !iNode.canConvertToInt()) {
                throw new IllegalArgumentException("Invalid specific participant index");
            }
            int idx = iNode.intValue();
            if (idx <= previousSpecIdx) {
                throw new IllegalArgumentException("Specific participant indices not ascending");
            }
            previousSpecIdx = idx;
            Fraction weighted = parseCanonicalFraction(entryNode, "n", "d");
            specWeighted.put(idx, weighted);
        }
        return new ParsedBucket(
                new BucketKey(defaultIndices, specificWeightTotal),
                new BucketValue(totalAmount, specWeighted));
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isTextual()) {
            throw new IllegalArgumentException("Missing text field: " + field);
        }
        return child.textValue();
    }

    private static Fraction parseCanonicalFraction(
            JsonNode node, String numField, String denField) {
        String numText = requireText(node, numField);
        String denText = requireText(node, denField);
        Fraction fraction = new Fraction(new BigInteger(numText), new BigInteger(denText));
        if (!numText.equals(fraction.numerator().toString())
                || !denText.equals(fraction.denominator().toString())) {
            throw new IllegalArgumentException("Non-canonical fraction encoding");
        }
        return fraction;
    }
}
