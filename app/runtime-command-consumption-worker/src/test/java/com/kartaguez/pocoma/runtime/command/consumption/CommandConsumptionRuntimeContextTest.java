package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;

import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.engine.command.execution.ExecuteRecordedCommandUseCase;
import com.kartaguez.pocoma.locator.consumption.command.CommandConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

@SpringBootTest(classes = PocomaCommandConsumptionWorkerApplication.class, properties = {
		"pocoma.command-consumption.enabled=false",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=none"
})
class CommandConsumptionRuntimeContextTest {
	@Autowired CommandConsumptionProperties properties;
	@Autowired CommandConsumptionLocator locator;
	@Autowired ConsumptionOrchestrator orchestrator;
	@Autowired ExecuteRecordedCommandUseCase executeRecordedCommand;
	@Autowired ConsumptionPollingWorker worker;
	@Autowired @Qualifier("commandConsumptionWorkerLifecycle") SmartLifecycle lifecycle;
	@Autowired Environment environment;

	@Test
	void composesTheGenericWorkerAndCommandLocatorWithConservativeDefaults() {
		assertNotNull(locator);
		assertNotNull(orchestrator);
		assertNotNull(executeRecordedCommand);
		assertNotNull(worker);
		assertInstanceOf(CommandConsumptionWorkerLifecycle.class, lifecycle);
		assertEquals(Duration.ofSeconds(30), properties.getClaimLease());
		assertEquals(100, properties.getMaxCandidatesInspected());
		assertEquals(10, properties.getMaxConsumptionsExecuted());
		assertEquals(Duration.ofSeconds(1), properties.getPollInterval());
		assertEquals(Duration.ofSeconds(5), properties.getRuntimeFailureBackoff());
		assertEquals("30s", environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"));
	}
}
