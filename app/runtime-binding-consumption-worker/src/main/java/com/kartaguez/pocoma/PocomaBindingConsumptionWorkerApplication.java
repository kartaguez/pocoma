package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import com.kartaguez.pocoma.runtime.binding.BindingRuntimeConfiguration;

@SpringBootApplication
@Import(BindingRuntimeConfiguration.class)
public class PocomaBindingConsumptionWorkerApplication {
	public static void main(String[] args) { SpringApplication.run(PocomaBindingConsumptionWorkerApplication.class, args); }
}
