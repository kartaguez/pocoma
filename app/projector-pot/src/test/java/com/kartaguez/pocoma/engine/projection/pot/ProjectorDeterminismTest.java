package com.kartaguez.pocoma.engine.projection.pot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;

class ProjectorDeterminismTest {
    @Test
    void sameHistoricalInputProducesSameAuthProjection() {
        var potId = PotId.of(UUID.fromString("10000000-0000-0000-0000-000000000001"));
        var key = new ProjectionKey(AuthProjectionDefinition.PROJECTION_TYPE,
                AuthProjectionDefinition.TARGET_OBJECT_TYPE,
                new TargetObjectId(potId.value().toString()), 3);
        var input = new AuthProjectionInput(potId, 3,
                UserId.of(UUID.fromString("20000000-0000-0000-0000-000000000001")), Map.of());
        var projector = new AuthProjector();

        assertEquals(projector.project(key, input), projector.project(key, input));
    }
}
