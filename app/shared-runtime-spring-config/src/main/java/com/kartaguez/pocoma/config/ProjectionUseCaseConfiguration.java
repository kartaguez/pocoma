package com.kartaguez.pocoma.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.kartaguez.pocoma.domain.projection.balance.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.event.TransactionAwareProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalanceProjectionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersProjectionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectedExpensePort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.projection.ProjectionUseCaseFactory;
import com.kartaguez.pocoma.config.event.ProjectionSpringEventPublisherAdapter;
import com.kartaguez.pocoma.infra.event.publisher.spring.SpringApplicationEventPublisher;

/** Spring composition for the projection path retained by the web/read runtime. */
@Configuration
public class ProjectionUseCaseConfiguration {

	@Bean
	PotBalancesCalculator potBalancesCalculator() {
		return new PotBalancesCalculator();
	}

	@Bean
	ComputePotBalancesUseCase computePotBalancesUseCase(
			PotBalanceProjectionPort balances,
			ProjectedExpensePort expenses,
			PotShareholdersProjectionPort shareholders,
			PotBalancesCalculator calculator,
			TransactionRunner transactions) {
		return ProjectionUseCaseFactory.computePotBalancesUseCase(
				balances, expenses, shareholders, calculator, transactions);
	}

	@Bean
	BuildProjectionTasksUseCase buildProjectionTasksUseCase(
			ProjectionTaskPort tasks,
			ProjectionEventPublisherPort events) {
		return ProjectionUseCaseFactory.buildProjectionTasksUseCase(tasks, events);
	}

	@Bean
	ExecuteProjectionTasksUseCase executeProjectionTasksUseCase(
			ComputePotBalancesUseCase balances,
			ProjectionEventPublisherPort events) {
		return ProjectionUseCaseFactory.executeProjectionTasksUseCase(balances, events);
	}

	@Bean
	@Primary
	ProjectionEventPublisherPort transactionAwareProjectionEventPublisherPort(
			SpringApplicationEventPublisher events,
			TransactionRunner transactions) {
		return new TransactionAwareProjectionEventPublisherPort(
				new ProjectionSpringEventPublisherAdapter(events), transactions);
	}
}
