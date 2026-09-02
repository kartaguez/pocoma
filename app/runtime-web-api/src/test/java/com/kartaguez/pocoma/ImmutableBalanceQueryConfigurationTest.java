package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.BeansException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kartaguez.pocoma.engine.port.out.query.PotBalancesQueryPort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaImmutablePotBalancesQueryAdapter;

class ImmutableBalanceQueryConfigurationTest {
	@Test void webRuntimeSelectsTheImmutableBalanceReader() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
			context.registerBean("legacyBalances", PotBalancesQueryPort.class,
					() -> mock(PotBalancesQueryPort.class));
			TestPropertyValues.of("pocoma.query.balance.pipeline-version=2").applyTo(context);
			context.register(ImmutableBalanceQueryConfiguration.class);
			context.refresh();
			assertInstanceOf(JpaImmutablePotBalancesQueryAdapter.class,
					context.getBean(PotBalancesQueryPort.class));
		}
	}

	@Test void webRuntimeFailsFastWithoutBalancePipelineVersion() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
			context.register(ImmutableBalanceQueryConfiguration.class);
			org.junit.jupiter.api.Assertions.assertThrows(BeansException.class, context::refresh);
			assertThat(context.getBeanFactory().getBeanDefinitionNames()).isNotNull();
		}
	}
}
