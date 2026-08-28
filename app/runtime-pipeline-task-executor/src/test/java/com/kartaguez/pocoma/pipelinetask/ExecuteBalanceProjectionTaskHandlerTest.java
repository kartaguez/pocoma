package com.kartaguez.pocoma.pipelinetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;

class ExecuteBalanceProjectionTaskHandlerTest {

	@Test
	void invokesProjectionUseCaseWithTypedTask() {
		RecordingComputeBalances useCase = new RecordingComputeBalances();
		ExecuteBalanceProjectionTaskHandler handler = new ExecuteBalanceProjectionTaskHandler(useCase);
		PotId potId = PotId.of(UUID.randomUUID());

		handler.execute(new ComputeBalancesTask(potId, 4));

		assertEquals(List.of(new Invocation(potId, 4)), useCase.invocations);
	}

	@Test
	void typedTaskRejectsInvalidVersion() {
		assertThrows(IllegalArgumentException.class,
				() -> new ComputeBalancesTask(PotId.of(UUID.randomUUID()), 0));
	}

	private record Invocation(PotId potId, long version) {
	}

	private static final class RecordingComputeBalances implements ComputePotBalancesUseCase {
		private final List<Invocation> invocations = new ArrayList<>();

		@Override
		public PotBalances computePotBalances(PotId potId, long targetVersion) {
			invocations.add(new Invocation(potId, targetVersion));
			return null;
		}

		@Override
		public PotBalances computePotBalancesFull(PotId potId, long targetVersion) {
			throw new UnsupportedOperationException();
		}
	}
}
