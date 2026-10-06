package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionRuntimeConfiguration;

@SpringBootApplication
@Import(EventConsumptionRuntimeConfiguration.class)
public class PocomaEventConsumptionWorkerApplication {
	public static void main(String[] args) {
		SpringApplication.run(PocomaEventConsumptionWorkerApplication.class, args);
	}
}
