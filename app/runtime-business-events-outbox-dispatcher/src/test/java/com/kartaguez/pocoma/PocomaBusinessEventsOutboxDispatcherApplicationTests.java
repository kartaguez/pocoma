package com.kartaguez.pocoma;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"pocoma.projection.worker.enabled=false",
		"pocoma.projection.nats.enabled=false"
})
class PocomaBusinessEventsOutboxDispatcherApplicationTests {

	@Test
	void contextLoads() {
	}
}
