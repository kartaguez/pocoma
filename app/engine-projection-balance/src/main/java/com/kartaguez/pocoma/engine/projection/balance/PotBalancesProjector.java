package com.kartaguez.pocoma.engine.projection.balance;

import java.math.BigDecimal;
import java.util.Map;

import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.engine.projection.task.engine.ProjectionProjector;


public final class PotBalancesProjector implements ProjectionProjector<PotBalances> {
	@Override
	public Projection project(ProjectionKey key, PotBalances balances) {
		if (!key.projectionType().equals(PotBalancesProjectionDefinition.PROJECTION_TYPE)
				|| !key.targetObjectType().equals(PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE)
				|| !key.targetObjectId().value().equals(balances.potId().value().toString())
				|| key.targetVersion() != balances.version()) throw new IllegalStateException("balances do not match key");
		var artifacts = balances.balances().values().stream().map(balance -> {
			var value = balance.value();
			return new ProjectionArtifact(PotBalancesProjectionDefinition.BALANCE,
					new ArtifactKey(balance.shareholderId().value().toString()), new JsonObject(Map.of(
							"shareholderId", new JsonString(balance.shareholderId().value().toString()),
							"balance", new JsonObject(Map.of(
									"numerator", new JsonNumber(BigDecimal.valueOf(value.numerator())),
									"denominator", new JsonNumber(BigDecimal.valueOf(value.denominator())))))));
		}).toList();
		return new Projection(key, artifacts);
	}
}
