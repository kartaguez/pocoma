package com.kartaguez.pocoma.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.kartaguez.pocoma.domain.projection.balance.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalanceProjectionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersProjectionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectedExpensePort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

class ProjectionUseCaseConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(ProjectionUseCaseConfiguration.class)
			.withBean(TransactionRunner.class, () -> mock(TransactionRunner.class))
			.withBean(PotBalanceProjectionPort.class, () -> mock(PotBalanceProjectionPort.class))
			.withBean(ProjectedExpensePort.class, () -> mock(ProjectedExpensePort.class))
			.withBean(PotShareholdersProjectionPort.class, () -> mock(PotShareholdersProjectionPort.class));

	@Test
	void createsProjectionUseCasesWhenPortsAreAvailable() {
		contextRunner.run(context -> {
			assertNotNull(context.getBean(PotBalancesCalculator.class));
			assertNotNull(context.getBean(ComputePotBalancesUseCase.class));
		});
	}
}
