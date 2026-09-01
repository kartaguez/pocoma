package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
public class PocomaBusinessEventsOutboxDispatcherApplication {

	@Bean
	@ConditionalOnMissingBean
	ObjectMapper businessEventsObjectMapper() {
		return new ObjectMapper();
	}

	public static void main(String[] args) {
		SpringApplication.run(PocomaBusinessEventsOutboxDispatcherApplication.class, args);
	}
}
