package com.kartaguez.pocoma;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kartaguez.pocoma.engine.port.out.query.PotBalancesQueryPort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaImmutablePotBalancesQueryAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
class ImmutableBalanceQueryConfiguration {
	@Bean
	@ConditionalOnMissingBean(ObjectMapper.class)
	ObjectMapper objectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}

	@Bean
	@Primary
	PotBalancesQueryPort immutablePotBalancesQueryPort(JdbcTemplate jdbc,
			@Value("${pocoma.query.balance.pipeline-id:balance-projection}") String pipelineId,
			@Value("${pocoma.query.balance.pipeline-version}") int pipelineVersion) {
		return new JpaImmutablePotBalancesQueryAdapter(jdbc, pipelineId, pipelineVersion);
	}
}
