package com.kartaguez.pocoma.pipeline.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.engine.taskexecution.model.TaskExecutionReport;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionArtifact;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionConflictException;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionPersistenceResult;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionReference;

class ExecuteBalanceProjectionTaskHandlerTest {
	@Test
	void exposesAStableCodeForAnImmutableProjectionConflict() {
		var exception = new BalanceProjectionConflictException();

		assertEquals("BALANCE_PROJECTION_CONFLICT", exception.failureCode());
		assertEquals("BALANCE_PROJECTION_CONFLICT", exception.failureCategory());
	}

	@Test
	void calculatesTheExactRequestedVersionAndReportsTheVersionedArtifact() {
		PotId potId = PotId.of(UUID.randomUUID());
		PipelineDefinition pipeline = new PipelineDefinition(PipelineId.of("balance-projection"), 2);
		long[] loadedVersion = new long[1];
		BalanceProjectionArtifact[] persisted = new BalanceProjectionArtifact[1];
		UUID projectionId = UUID.randomUUID();
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		var handler = new ExecuteBalanceProjectionTaskHandler(pipeline, (requestedPot, requestedVersion) -> {
			loadedVersion[0] = requestedVersion;
			return new PotBalances(requestedPot, requestedVersion, Map.of());
		}, artifact -> {
			persisted[0] = artifact;
			return new BalanceProjectionPersistenceResult.Created(
					new BalanceProjectionReference(projectionId, artifact.identity(), createdAt));
		});

		var report = assertInstanceOf(TaskExecutionReport.Succeeded.class,
				handler.execute(new ComputeBalancesTask(potId, 42)));

		assertEquals(42, loadedVersion[0]);
		assertEquals(42, persisted[0].identity().potVersion());
		assertEquals(2, persisted[0].identity().pipeline().pipelineVersion());
		assertEquals(42, report.inputs().getFirst().version());
		assertEquals(projectionId.toString(), report.artifacts().getFirst().id());
		assertEquals(2, report.artifacts().getFirst().version().orElseThrow());
		assertEquals(42, report.artifacts().getFirst().subject().orElseThrow().version());
	}
}
