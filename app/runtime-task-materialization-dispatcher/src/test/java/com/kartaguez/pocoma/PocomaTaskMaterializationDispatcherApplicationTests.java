package com.kartaguez.pocoma;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"pocoma.pipeline.materialization.enabled=false",
		"pocoma.task-materialization.nats.enabled=false"
})
class PocomaTaskMaterializationDispatcherApplicationTests {

	@Test
	void contextLoads() {
	}
}
