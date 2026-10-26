package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import javax.sql.DataSource;

/** Fresh V1..V5 + canonical routing. No historical target, reset or worker privileges. */
final class I2Schema {
    static void migrate(DataSource source, String schema) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("create extension if not exists pgcrypto with schema public");
        jdbc.execute("create role " + schema + "_runtime nologin");
        Flyway.configure()
                .dataSource(source)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                .locations(
                        "classpath:db/v3-pot/migration",
                        "classpath:db/v3-pot-execution/migration",
                        "classpath:db/v3-consumption-write/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                jdbc.queryForObject("select current_database()", String.class),
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                jdbc.queryForObject("select current_user", String.class),
                                "expectedRuntime",
                                schema + "_runtime"))
                .load()
                .migrate();
        jdbc.update(
                "insert into consumption_routing_contract values"
                        + " ('98f7268d-d340-581c-b83d-010926223e14','COMMAND','sha256-v1',4)");
    }
}
