package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.kartaguez.pocoma.runtime.command.consumption.CommandConsumptionRuntimeConfiguration;

@SpringBootApplication
@Import(CommandConsumptionRuntimeConfiguration.class)
public class PocomaCommandConsumptionWorkerApplication {
	public static void main(String[] args) {
		SpringApplication.run(PocomaCommandConsumptionWorkerApplication.class, args);
	}
}
