package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class PotCommandJsonTest {
    @Test
    void allThreeTargetListsRoundTripAndDurableCorruptionIsExplicitlyTechnical() {
        var id = new ShareholderId(UUID.randomUUID());
        var valid =
                List.of(
                        new AddShareholders(
                                List.of(
                                        new ShareholderTransitions.Creation(
                                                id,
                                                new Name("A"),
                                                Optional.empty(),
                                                new Weight(Fraction.ONE)))),
                        new UpdateShareholderDetails(
                                List.of(
                                        new ShareholderTransitions.DetailsIntent(
                                                id, new Name("A"), Optional.empty()))),
                        new UpdateShareholderWeights(
                                List.of(
                                        new ShareholderTransitions.WeightIntent(
                                                id, new Weight(Fraction.ONE)))));
        for (var intent : valid) {
            String operation = intent.getClass().getSimpleName();
            String encoded = PotCommandJson.encode(intent);
            assertEquals(intent, PotCommandJson.decode(operation, encoded));
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            try {
                var target = json.readTree(encoded).get("targets").get(0);
                for (String broken :
                        List.of(
                                "{}",
                                "{\"targets\":null}",
                                "{\"targets\":[]}",
                                "{\"targets\":[null]}",
                                "{\"targets\":[" + target + "," + target + "]}"))
                    assertThrows(
                            InconsistentPotFactsException.class,
                            () -> PotCommandJson.decode(operation, broken),
                            operation + " " + broken);
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
