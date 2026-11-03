package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;

import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.*;
import java.nio.file.*;

final class ReadStoreFixture {
    static PostgreSQLContainer container() { return new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73").asCompatibleSubstituteFor("postgres")); }
    final PostgreSQLContainer pg; final String schema,adminRole,runtimeRole;
    final DataSource admin,source; final JdbcTemplate oracle,sql; final SpringAttemptTransactions tx;
    ReadStoreFixture(PostgreSQLContainer pg) { this(pg,"3"); }
    ReadStoreFixture(PostgreSQLContainer pg,String target) {
        this.pg=pg; schema="l4_"+UUID.randomUUID().toString().replace("-",""); adminRole=schema+"_a"; runtimeRole=schema+"_r";
        var root=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
        root.execute("create extension if not exists pgcrypto with schema public");
        root.execute("create role "+adminRole+" login password 'fixture'"); root.execute("create role "+runtimeRole+" login password 'fixture'");
        root.execute("create schema "+schema+" authorization "+adminRole); root.execute("grant usage on schema "+schema+" to "+runtimeRole);
        admin=ds(adminRole); source=ds(runtimeRole); oracle=new JdbcTemplate(admin); sql=new JdbcTemplate(source);
        migrate(target); var manager=new DataSourceTransactionManager(source); manager.setNestedTransactionAllowed(false);
        tx=new SpringAttemptTransactions(source,manager);
    }
    DataSource ds(String role) { return new DriverManagerDataSource(pg.getJdbcUrl()+"&currentSchema="+schema+"&socketTimeout=20",role,"fixture"); }
    int migrate(String target) { return Flyway.configure().dataSource(admin).schemas(schema).defaultSchema(schema).createSchemas(false)
        .locations("classpath:db/v3-consumption-read/migration","classpath:db/v3-read-business/migration")
        .target(target).placeholders(Map.of("expectedDatabase",pg.getDatabaseName(),"expectedSchema",schema,"expectedAdmin",adminRole,"expectedRuntime",runtimeRole)).load().migrate().migrationsExecuted; }
    <T> T write(Supplier<T> action) { return tx.write(() -> { var result=action.get(); SpringAttemptTransactions.closePublication(source); return result; }); }
    void write(Runnable action) { write(() -> { action.run(); return null; }); }
    void awaitLock(String relation) throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {
            var waiting=sql.queryForObject("select count(*) from pg_stat_activity where usename=? and wait_event_type='Lock' and query like ?",Integer.class,runtimeRole,"%"+relation+"%");
            if(waiting>0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("No physical PostgreSQL lock wait for "+relation);
    }
    void evidence(String scenario,Object value) throws Exception {
        var p=Path.of("target/l4-read-evidence",schema+".txt"); Files.createDirectories(p.getParent());
        Files.writeString(p,scenario+" "+value+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}
