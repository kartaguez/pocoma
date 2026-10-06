package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.kartaguez.pocoma.runtime.result.CommandResultRuntimeConfiguration;

@SpringBootApplication
@Import(CommandResultRuntimeConfiguration.class)
public class PocomaCommandResultConsumptionWorkerApplication {
	public static void main(String[] args) {
		SpringApplication.run(PocomaCommandResultConsumptionWorkerApplication.class, args);
	}
}
