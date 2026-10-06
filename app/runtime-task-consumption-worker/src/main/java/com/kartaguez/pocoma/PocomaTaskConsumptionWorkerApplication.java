package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskRuntimeConfiguration;

@SpringBootApplication
@Import(CanonicalProjectionTaskRuntimeConfiguration.class)
public class PocomaTaskConsumptionWorkerApplication {
	public static void main(String[] args) {
		SpringApplication.run(PocomaTaskConsumptionWorkerApplication.class, args);
	}
}
