package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;

@SpringBootTest(
		classes = PocomaCommandConsumptionWorkerApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
			"pocoma.command-consumption.enabled=true",
			"spring.flyway.enabled=false",
			"spring.jpa.hibernate.ddl-auto=none"
		})
class CommandConsumptionActuatorTest {
	@LocalServerPort int port;

	@Test
	void exposesHealthProbesAndPrometheusWhenWorkerIsEnabled() throws Exception {
		assertOk("/actuator/health");
		assertOk("/actuator/health/liveness");
		assertOk("/actuator/health/readiness");

		HttpResponse<String> prometheus = get("/actuator/prometheus");
		assertEquals(200, prometheus.statusCode());
		assertTrue(prometheus.body().contains("pocoma_consumption_worker_running"));
		assertTrue(prometheus.body().contains("family=\"command\""));
	}

	private void assertOk(String path) throws Exception {
		assertEquals(200, get(path).statusCode(), path);
	}

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}
}
