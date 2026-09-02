package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kartaguez.pocoma.engine.port.out.query.PotBalancesQueryPort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaImmutablePotBalancesQueryAdapter;

class ImmutableBalanceQueryConfigurationTest {
	@Test void webRuntimeSelectsTheImmutableBalanceReader() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
			context.registerBean("legacyBalances", PotBalancesQueryPort.class,
					() -> mock(PotBalancesQueryPort.class));
			context.register(ImmutableBalanceQueryConfiguration.class);
			context.refresh();
			assertInstanceOf(JpaImmutablePotBalancesQueryAdapter.class,
					context.getBean(PotBalancesQueryPort.class));
		}
	}
}
