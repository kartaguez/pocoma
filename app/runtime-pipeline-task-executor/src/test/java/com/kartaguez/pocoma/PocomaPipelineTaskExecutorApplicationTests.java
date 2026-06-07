package com.kartaguez.pocoma;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "pocoma.pipeline.task-execution.enabled=false")
class PocomaPipelineTaskExecutorApplicationTests {

	@Test
	void contextLoads() {
	}
}
