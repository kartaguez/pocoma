package com.kartaguez.pocoma.runtime.event.consumption;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.processing.event.materialization.ProjectionMaterializationPolicy;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionSource;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

class EventConsumptionRuntimeSpringBindingTest {
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(EventConsumptionRuntimeConfiguration.class, InfrastructureConfiguration.class);

	@Test
	void startsWithOneExplicitProjectionTypeAndDerivesItsRoutesFromTheCanonicalPolicy() {
		contextRunner.withPropertyValues("pocoma.event-consumption.projection-types=READ_POT")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(ProjectionMaterializationConsumptionSource.class);
					assertThat(context).hasSingleBean(ConsumptionOrchestrator.class);
					assertThat(context).hasSingleBean(ConsumptionPollingWorker.class);
					assertThat(context.getBean(EventConsumptionProperties.class).getProjectionTypes())
							.containsExactly("READ_POT");
					assertThat(routes(context.getBean(ProjectionMaterializationConsumptionSource.class)))
							.isEqualTo(expectedRoutes(context, Set.of(
									ReadPotProjectionDefinition.PROJECTION_TYPE)));
				});
	}

	@Test
	void startsWithMultipleExplicitProjectionTypesAndDerivesTheirRoutesFromTheCanonicalPolicy() {
		contextRunner.withPropertyValues(
				"pocoma.event-consumption.projection-types=READ_POT,POT_BALANCES")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(EventConsumptionProperties.class).getProjectionTypes())
							.containsExactly("READ_POT", "POT_BALANCES");
					assertThat(routes(context.getBean(ProjectionMaterializationConsumptionSource.class)))
							.isEqualTo(expectedRoutes(context, Set.of(
									ReadPotProjectionDefinition.PROJECTION_TYPE,
									PotBalancesProjectionDefinition.PROJECTION_TYPE)));
				});
	}

	@Test
	void failsStartupWhenProjectionTypesAreMissing() {
		contextRunner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
					.hasRootCauseMessage("projection-types must not be empty");
		});
	}

	@Test
	void failsStartupWhenAProjectionTypeIsNotMaterializableByTheCanonicalPolicy() {
		contextRunner.withPropertyValues(
				"pocoma.event-consumption.projection-types=NOT_MATERIALIZABLE")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasRootCauseMessage(
									"projection-types contain unsupported values: [ProjectionType[value=NOT_MATERIALIZABLE]]");
				});
	}

	@Test
	void failsStartupWhenTheBoundProjectionTypeListContainsADuplicate() {
		contextRunner.withPropertyValues(
				"pocoma.event-consumption.projection-types=READ_POT,READ_POT")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasRootCauseMessage("projection-types must not contain duplicates");
				});
	}

	private static Map<EventType, Set<ProjectionType>> expectedRoutes(
			org.springframework.context.ApplicationContext context, Set<ProjectionType> projectionTypes) {
		return context.getBean(ProjectionMaterializationPolicy.class).materializationsFor(projectionTypes);
	}

	@SuppressWarnings("unchecked")
	private static Map<EventType, Set<ProjectionType>> routes(ProjectionMaterializationConsumptionSource source) {
		return (Map<EventType, Set<ProjectionType>>) ReflectionTestUtils.getField(source, "routes");
	}

	@Configuration(proxyBeanMethods = false)
	static class InfrastructureConfiguration {
		@Bean
		PlatformTransactionManager transactionManager() {
			return inertProxy(PlatformTransactionManager.class);
		}

		@Bean
		JdbcTemplate jdbcTemplate() {
			return new JdbcTemplate(inertProxy(DataSource.class));
		}

		@Bean
		JpaConsumptionSlotRepository consumptionSlots() {
			return inertProxy(JpaConsumptionSlotRepository.class);
		}

		@Bean
		JpaConsumptionClaimRepository consumptionClaims() {
			return inertProxy(JpaConsumptionClaimRepository.class);
		}
	}

	private static <T> T inertProxy(Class<T> contract) {
		return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] { contract },
				(proxy, method, arguments) -> switch (method.getName()) {
					case "toString" -> "inert " + contract.getSimpleName();
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == arguments[0];
					default -> throw new UnsupportedOperationException(method.toString());
				}));
	}
}
