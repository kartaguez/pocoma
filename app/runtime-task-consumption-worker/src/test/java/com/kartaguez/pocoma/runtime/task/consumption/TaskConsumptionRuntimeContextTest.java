package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.locator.consumption.task.TaskConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

@SpringBootTest(properties = {
		"pocoma.task-consumption.enabled=false",
		"pocoma.task-consumption.pipeline-id=balance-projection",
		"pocoma.task-consumption.pipeline-version=2",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class TaskConsumptionRuntimeContextTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private TaskConsumptionLocator locator;
	@Autowired private ConsumptionOrchestrator orchestrator;
	@Autowired private ConsumptionPollingWorker worker;

	@Test
	void assemblesTheBalanceTaskRuntimeButKeepsItDisabledByDefault() {
		assertNotNull(locator);
		assertNotNull(orchestrator);
		assertFalse(worker.isRunning());
	}
}
