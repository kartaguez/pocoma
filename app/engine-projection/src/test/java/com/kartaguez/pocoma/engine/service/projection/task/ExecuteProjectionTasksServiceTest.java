package com.kartaguez.pocoma.engine.service.projection.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;

class ExecuteProjectionTasksServiceTest {

	@Test
	void executesBalanceCalculationInstruction() {
		RecordingComputePotBalancesUseCase computeUseCase = new RecordingComputePotBalancesUseCase();
		ExecuteProjectionTasksService service = new ExecuteProjectionTasksService(computeUseCase);
		PotId potId = PotId.of(UUID.randomUUID());

		service.executeProjectionTask(new ExecuteProjectionTaskCommand(potId, 12));

		assertEquals(potId, computeUseCase.potId);
		assertEquals(12, computeUseCase.targetVersion);
	}

	private static final class RecordingComputePotBalancesUseCase implements ComputePotBalancesUseCase {
		private PotId potId;
		private long targetVersion;

		@Override
		public PotBalances computePotBalances(PotId potId, long targetVersion) {
			this.potId = potId;
			this.targetVersion = targetVersion;
			return new PotBalances(potId, targetVersion, Map.of());
		}

		@Override
		public PotBalances computePotBalancesFull(PotId potId, long targetVersion) {
			return computePotBalances(potId, targetVersion);
		}
	}
}
