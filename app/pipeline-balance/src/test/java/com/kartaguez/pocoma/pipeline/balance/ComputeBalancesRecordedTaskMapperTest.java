package com.kartaguez.pocoma.pipeline.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

class ComputeBalancesRecordedTaskMapperTest {
	private static final PipelineDefinition PIPELINE = new PipelineDefinition(PipelineId.of("balance-projection"), 2);
	private final ComputeBalancesRecordedTaskMapper mapper = new ComputeBalancesRecordedTaskMapper(PIPELINE,
			new ObjectMapper());

	@Test
	void validatesAndUsesTheDurableExactVersion() {
		PotId potId = PotId.of(UUID.randomUUID());
		var result = mapper.map(task(potId, 42,
				"{\"potId\":\"" + potId.value() + "\",\"targetVersion\":42}"));

		assertEquals(potId, result.task().potId());
		assertEquals(42, result.task().targetVersion());
	}

	@Test
	void rejectsPayloadWhoseVersionDiffersFromTheDurableColumn() {
		PotId potId = PotId.of(UUID.randomUUID());
		assertThrows(ComputeBalancesRecordedTaskMapper.InvalidBalanceTaskException.class,
				() -> mapper.map(task(potId, 42,
						"{\"potId\":\"" + potId.value() + "\",\"targetVersion\":50}")));
	}

	private static RecordedTask task(PotId potId, long version, String payload) {
		return new RecordedTask(UUID.randomUUID(), PIPELINE, potId, version,
				Instant.parse("2026-01-01T00:00:00Z"), BalancePipeline.TASK_TYPE, payload, Optional.empty());
	}
}
