package com.kartaguez.pocoma.infra.tx.spring.v3;

import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

/** No application bootstrap: explicit Flyway location, entity scan and transaction authorities. */
@Testcontainers
abstract class PostgresFoundationTestSupport {
    static final String LOCATION = "classpath:db/v3-foundation/migration";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse(
                                    "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                            .asCompatibleSubstituteFor("postgres"));

    Realm write;
    Realm read;

    @BeforeEach
    void createRealms() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        write = new Realm("v3_write_" + suffix);
        try {
            read = new Realm("v3_read_" + suffix);
        } catch (RuntimeException failure) {
            write.close();
            throw failure;
        }
    }

    @AfterEach
    void closeRealms() {
        if (read != null) read.close();
        if (write != null) write.close();
        // No clean/drop against configurable external databases. Container lifecycle owns disposal.
    }

    static final class Realm implements AutoCloseable {
        final String schema;
        final DataSource dataSource;
        final JdbcTemplate jdbc;
        final Flyway flyway;
        final EntityManagerFactory emf;
        final JpaTransactionManager manager;
        final SpringTransactionRunner runner;
        private final LocalContainerEntityManagerFactoryBean factory;

        Realm(String schema) {
            this.schema = schema;
            String url = POSTGRES.getJdbcUrl();
            dataSource =
                    new DriverManagerDataSource(
                            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                            POSTGRES.getUsername(),
                            POSTGRES.getPassword());
            jdbc = new JdbcTemplate(dataSource);
            flyway =
                    Flyway.configure()
                            .dataSource(dataSource)
                            .defaultSchema(schema)
                            .schemas(schema)
                            .createSchemas(true)
                            .locations(LOCATION)
                            .cleanDisabled(true)
                            .load();
            flyway.migrate();
            factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.kartaguez.pocoma.infra.tx.spring.v3.fixture");
            factory.setPersistenceUnitName(schema);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(
                    Map.of(
                            "hibernate.hbm2ddl.auto",
                            "validate",
                            "hibernate.default_schema",
                            schema));
            factory.afterPropertiesSet();
            emf = factory.getObject();
            manager = new JpaTransactionManager(emf);
            manager.setDataSource(dataSource);
            runner = new SpringTransactionRunner(new TransactionTemplate(manager));
        }

        int observedEffects() {
            // DriverManager deliberately bypasses every Spring-bound connection.
            try (var connection =
                            DriverManager.getConnection(
                                    POSTGRES.getJdbcUrl(),
                                    POSTGRES.getUsername(),
                                    POSTGRES.getPassword());
                    var statement = connection.createStatement();
                    var result =
                            statement.executeQuery(
                                    "select count(*) from " + schema + ".foundation_effect")) {
                result.next();
                return result.getInt(1);
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void close() {
            factory.destroy();
        }
    }
}
