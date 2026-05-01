package com.kartaguez.pocoma.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.kartaguez.pocoma.domain.policy.AddPotShareholdersAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.CreateExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.DeleteExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.DeletePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdateExpenseDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdateExpenseSharesAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdatePotDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdatePotShareholdersDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.UpdatePotShareholdersWeightsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.projection.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.command.usecase.AddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreateExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeletePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersWeightsUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
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
import com.kartaguez.pocoma.engine.service.command.CommandUseCaseFactory;
import com.kartaguez.pocoma.engine.service.projection.ProjectionUseCaseFactory;
import com.kartaguez.pocoma.infra.event.publisher.spring.OutboxThenSpringEventPublisherAdapter;
import com.kartaguez.pocoma.observability.api.NoopPocomaObservation;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.event.ObservedEventPublisherPort;

@Configuration
public class CommandUseCaseConfiguration {

	@Bean
	PotBalancesCalculator potBalancesCalculator() {
		return new PotBalancesCalculator();
	}

	@Bean
	ComputePotBalancesUseCase computePotBalancesUseCase(
			PotBalancesPort potBalancesPort,
			ProjectedExpensePort projectedExpensePort,
			PotBalancesCalculator potBalancesCalculator,
			TransactionRunner transactionRunner) {
		return ProjectionUseCaseFactory.computePotBalancesUseCase(
				potBalancesPort,
				projectedExpensePort,
				potBalancesCalculator,
				transactionRunner);
	}

	@Bean
	BuildProjectionTasksUseCase buildProjectionTasksUseCase(
			BusinessEventOutboxPort outboxPort,
			ProjectionTaskPort projectionTaskPort) {
		return ProjectionUseCaseFactory.buildProjectionTasksUseCase(outboxPort, projectionTaskPort);
	}

	@Bean
	ExecuteProjectionTasksUseCase executeProjectionTasksUseCase(
			ProjectionTaskPort projectionTaskPort,
			ComputePotBalancesUseCase computePotBalancesUseCase) {
		return ProjectionUseCaseFactory.executeProjectionTasksUseCase(projectionTaskPort, computePotBalancesUseCase);
	}

	@Bean
	EventPublisherPort outboxThenSpringEventPublisherPort(
			@Qualifier("jpaBusinessEventOutboxAdapter") BusinessEventOutboxPort outboxPort,
			ApplicationEventPublisher applicationEventPublisher,
			TransactionRunner transactionRunner) {
		return new OutboxThenSpringEventPublisherAdapter(outboxPort, applicationEventPublisher, transactionRunner);
	}

	@Bean
	@Primary
	EventPublisherPort observedOutboxEventPublisherPort(
			@Qualifier("outboxThenSpringEventPublisherPort") EventPublisherPort delegate,
			PocomaObservation observation) {
		return new ObservedEventPublisherPort(delegate, observation);
	}

	@Bean
	@ConditionalOnMissingBean(PocomaObservation.class)
	PocomaObservation pocomaObservation() {
		return new NoopPocomaObservation();
	}

	@Bean
	CreatePotUseCase createPotUseCase(
			PotGlobalVersionPort potGlobalVersionPort,
			PotHeaderPort potHeaderPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.createPotUseCase(
				potGlobalVersionPort,
				potHeaderPort,
				eventPublisherPort,
				new CreatePotAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	CreateExpenseUseCase createExpenseUseCase(
			PotContextPort potContextPort,
			PotGlobalVersionPort potGlobalVersionPort,
			ExpenseHeaderPort expenseHeaderPort,
			ExpenseSharesPort expenseSharesPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.createExpenseUseCase(
				potContextPort,
				potGlobalVersionPort,
				expenseHeaderPort,
				expenseSharesPort,
				eventPublisherPort,
				new CreateExpenseAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	AddPotShareholdersUseCase addPotShareholdersUseCase(
			PotContextPort potContextPort,
			PotShareholdersPort potShareholdersPort,
			PotGlobalVersionPort potGlobalVersionPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.addPotShareholdersUseCase(
				potContextPort,
				potShareholdersPort,
				potGlobalVersionPort,
				potShareholdersPort,
				eventPublisherPort,
				new AddPotShareholdersAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	DeletePotUseCase deletePotUseCase(
			PotContextPort potContextPort,
			PotHeaderPort potHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.deletePotUseCase(
				potContextPort,
				potHeaderPort,
				potGlobalVersionPort,
				potHeaderPort,
				eventPublisherPort,
				new DeletePotAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	DeleteExpenseUseCase deleteExpenseUseCase(
			ExpenseContextPort expenseContextPort,
			ExpenseHeaderPort expenseHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.deleteExpenseUseCase(
				expenseContextPort,
				expenseHeaderPort,
				potGlobalVersionPort,
				expenseHeaderPort,
				eventPublisherPort,
				new DeleteExpenseAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	UpdatePotDetailsUseCase updatePotDetailsUseCase(
			PotContextPort potContextPort,
			PotHeaderPort potHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.updatePotDetailsUseCase(
				potContextPort,
				potHeaderPort,
				potGlobalVersionPort,
				potHeaderPort,
				eventPublisherPort,
				new UpdatePotDetailsAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	UpdateExpenseDetailsUseCase updateExpenseDetailsUseCase(
			ExpenseContextPort expenseContextPort,
			ExpenseHeaderPort expenseHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.updateExpenseDetailsUseCase(
				expenseContextPort,
				expenseHeaderPort,
				potGlobalVersionPort,
				expenseHeaderPort,
				eventPublisherPort,
				new UpdateExpenseDetailsAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	UpdateExpenseSharesUseCase updateExpenseSharesUseCase(
			ExpenseContextPort expenseContextPort,
			ExpenseSharesPort expenseSharesPort,
			PotGlobalVersionPort potGlobalVersionPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.updateExpenseSharesUseCase(
				expenseContextPort,
				expenseSharesPort,
				potGlobalVersionPort,
				expenseSharesPort,
				eventPublisherPort,
				new UpdateExpenseSharesAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	UpdatePotShareholdersDetailsUseCase updatePotShareholdersDetailsUseCase(
			PotContextPort potContextPort,
			PotShareholdersPort potShareholdersPort,
			PotGlobalVersionPort potGlobalVersionPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.updatePotShareholdersDetailsUseCase(
				potContextPort,
				potShareholdersPort,
				potGlobalVersionPort,
				potShareholdersPort,
				eventPublisherPort,
				new UpdatePotShareholdersDetailsAuthorizationPolicy(),
				transactionRunner);
	}

	@Bean
	UpdatePotShareholdersWeightsUseCase updatePotShareholdersWeightsUseCase(
			PotContextPort potContextPort,
			PotShareholdersPort potShareholdersPort,
			PotGlobalVersionPort potGlobalVersionPort,
			EventPublisherPort eventPublisherPort,
			TransactionRunner transactionRunner) {
		return CommandUseCaseFactory.updatePotShareholdersWeightsUseCase(
				potContextPort,
				potShareholdersPort,
				potGlobalVersionPort,
				potShareholdersPort,
				eventPublisherPort,
				new UpdatePotShareholdersWeightsAuthorizationPolicy(),
				transactionRunner);
	}
}
