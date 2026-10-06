package com.kartaguez.pocoma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PocomaWebRuntimeConfiguration.class)
public class PocomaWebApiApplication {
	public static void main(String[] args) {
		SpringApplication.run(PocomaWebApiApplication.class, args);
	}
}
