package com.kartaguez.pocoma.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.domain.projection.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectedExpensePort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.projection.ProjectionUseCaseFactory;

@Configuration
public class ProjectionUseCaseConfiguration {

	@Bean
	PotBalancesCalculator potBalancesCalculator() {
		return new PotBalancesCalculator();
	}

	@Bean
	ComputePotBalancesUseCase computePotBalancesUseCase(
			PotBalancesPort potBalancesPort,
			ProjectedExpensePort projectedExpensePort,
			PotShareholdersPort potShareholdersPort,
			PotBalancesCalculator potBalancesCalculator,
			TransactionRunner transactionRunner) {
		return ProjectionUseCaseFactory.computePotBalancesUseCase(
				potBalancesPort,
				projectedExpensePort,
				potShareholdersPort,
				potBalancesCalculator,
				transactionRunner);
	}
}
