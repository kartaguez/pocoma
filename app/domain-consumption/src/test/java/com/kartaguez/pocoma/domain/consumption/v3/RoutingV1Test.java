package com.kartaguez.pocoma.domain.consumption.v3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.UUID;

class RoutingV1Test {
    @Test
    void twentyOneIndependentVectorsAndSignedExtremes() throws Exception {
        var rows =
                Files.readAllLines(
                        Path.of("../../docs/testing/evidence/L2-I1/routing-vectors.csv"));
        assertEquals(22, rows.size());
        for (var line : rows.subList(1, rows.size())) {
            var v = line.split(",");
            int hash = RoutingV1.hash(ConsumableFamily.valueOf(v[0]), UUID.fromString(v[1]));
            assertEquals(Integer.parseInt(v[3]), hash, line);
            assertEquals(Integer.parseInt(v[4]), RoutingV1.partition(hash, 4), line);
            assertEquals(Integer.parseInt(v[5]), RoutingV1.partition(hash, 16), line);
        }
        assertEquals(0, RoutingV1.partition(Integer.MIN_VALUE, 4));
        assertEquals(3, RoutingV1.partition(Integer.MAX_VALUE, 4));
        assertEquals(1, RoutingV1.partition(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> RoutingV1.partition(0, 0));
        assertThrows(IllegalArgumentException.class, () -> RoutingV1.partition(0, -1));
        assertThrows(NullPointerException.class, () -> new ConsumptionId(null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ConsumptionDefinition(
                                new ConsumptionId(UUID.randomUUID()),
                                ConsumableFamily.POT_EVENT,
                                ConsumptionDefinition.Authority.WRITE));
    }
}
