package com.kartaguez.pocoma.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.kartaguez.pocoma.engine.port.in.command.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.out.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectedExpensePort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.infra.event.publisher.spring.OutboxThenSpringEventPublisherAdapter;
import com.kartaguez.pocoma.observability.event.ObservedEventPublisherPort;

class CommandUseCaseConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(CommandUseCaseConfiguration.class)
			.withBean(
					"jpaBusinessEventOutboxAdapter",
					BusinessEventOutboxPort.class,
					() -> mock(BusinessEventOutboxPort.class))
			.withBean(ProjectionTaskPort.class, () -> mock(ProjectionTaskPort.class))
			.withBean(TransactionRunner.class, () -> mock(TransactionRunner.class))
			.withBean(PotGlobalVersionPort.class, () -> mock(PotGlobalVersionPort.class))
			.withBean(PotHeaderPort.class, () -> mock(PotHeaderPort.class))
			.withBean(PotBalancesPort.class, () -> mock(PotBalancesPort.class))
			.withBean(ProjectedExpensePort.class, () -> mock(ProjectedExpensePort.class))
			.withBean(PotContextPort.class, () -> mock(PotContextPort.class))
			.withBean(PotShareholdersPort.class, () -> mock(PotShareholdersPort.class))
			.withBean(ExpenseContextPort.class, () -> mock(ExpenseContextPort.class))
			.withBean(ExpenseHeaderPort.class, () -> mock(ExpenseHeaderPort.class))
			.withBean(ExpenseSharesPort.class, () -> mock(ExpenseSharesPort.class));

	@Test
	void createsCommandUseCasesWhenPortsAndPoliciesAreAvailable() {
		contextRunner
				.run(context -> assertNotNull(context.getBean(CreatePotUseCase.class)));
	}

	@Test
	void exposesObservedOutboxEventPublisherAsPrimaryEventPublisherPort() {
		contextRunner
				.run(context -> assertInstanceOf(
						ObservedEventPublisherPort.class,
						context.getBean(EventPublisherPort.class)));
	}

	@Test
	void exposesOutboxThenSpringPublisherAsObservedDelegate() {
		contextRunner
				.run(context -> assertInstanceOf(
						OutboxThenSpringEventPublisherAdapter.class,
						context.getBean("outboxThenSpringEventPublisherPort")));
	}
}
