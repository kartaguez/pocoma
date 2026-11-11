package com.kartaguez.pocoma.infra.postgres.read.v3;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.kartaguez.pocoma.domain.projection.v3.PotVersion;

/** Canonical typed equality, strict fields, numbers, nulls, UUIDs and array ordering. */
public final class PotVersionJson {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private PotVersionJson() {}
    public static String encode(PotVersion value) { return JSON.valueToTree(value).toString(); }
    public static PotVersion decode(int schema, String payload) {
        try {
            if(schema!=1) throw new IllegalArgumentException();
            var tree=JSON.readTree(payload); var value=JSON.treeToValue(tree,PotVersion.class);
            if(!tree.equals(JSON.valueToTree(value))) throw new IllegalArgumentException();
            return value;
        } catch(java.io.IOException | RuntimeException invalid) { throw new IllegalStateException("Corrupt PotVersion"); }
    }
}
