package com.kartaguez.pocoma.runtime.command.consumption;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Startup compatibility is checked against CURRENT PostgreSQL, including the real V30 mode. */
abstract class PreparedCommandPostgresTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }
}
