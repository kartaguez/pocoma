package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
public class PocomaWebApiApplication {

	@Bean
	@ConditionalOnMissingBean
	ObjectMapper webApiObjectMapper() {
		return new ObjectMapper().findAndRegisterModules();
	}

	public static void main(String[] args) {
		SpringApplication.run(PocomaWebApiApplication.class, args);
	}
}
