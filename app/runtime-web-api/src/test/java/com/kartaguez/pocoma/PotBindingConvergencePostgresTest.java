package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.useridentity.BindingDetachResult;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBinding;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBindingStatus;
import com.kartaguez.pocoma.engine.read.currentbinding.GetCurrentBindingUseCase;
import com.kartaguez.pocoma.engine.read.pot.ReadPotForExternalIdentityService;
import com.kartaguez.pocoma.engine.read.pot.ReadPotResult;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.JdbcCurrentBindingAdapter;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

/** The PRIMARY detach is committed before the READ projection changes. */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.test/jwks"})
@ActiveProfiles("postgres")
@Testcontainers
class PotBindingConvergencePostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired DataSource dataSource;
    @Autowired ExternalIdentityBindingPort authority;
    @Autowired TransactionRunner transactions;
    @Autowired GetCurrentBindingUseCase projectedBindings;

    @Test void attachStaleDetachConvergedDetachAndRebindUseTheProjectedUser() {
        var jdbc = new JdbcTemplate(dataSource);
        var projection = new JdbcCurrentBindingAdapter(jdbc, "pocoma_read");
        var e = new ExternalIdentity("issuer", "convergence");
        var u1 = new PocomaUserId(UUID.randomUUID());
        var u2 = new PocomaUserId(UUID.randomUUID());
        jdbc.update("insert into users(user_id) values (?),(?)", u1.value(), u2.value());
        var seen = new AtomicReference<UserId>();
        var read = new ReadPotForExternalIdentityService((user, capabilities, pot, version) -> {
            seen.set(user);
            return new ReadPotResult.Forbidden();
        }, projectedBindings, new ExternalAuthorityPermissionTranslator());
        var pot = new PotId(UUID.randomUUID());
        var authorities = Set.of("pocoma:pot:view");

        read.read(e, authorities, pot, 1);
        assertNull(seen.get()); // absent
        var b1 = transactions.runInTransaction(() -> authority.acquire(e, u1)).bindingId();
        var first = new CurrentBinding(e, new BindingRevision(1), CurrentBindingStatus.ATTACHED,
                u1, b1, UUID.randomUUID(), Instant.now());
        transactions.runInTransaction(() -> projection.apply(first));
        read.read(e, authorities, pot, 1);
        assertEquals(new UserId(u1.value()), seen.get());

        assertEquals(BindingDetachResult.DETACHED, transactions.runInTransaction(() -> authority.detach(e, b1)));
        seen.set(null);
        read.read(e, authorities, pot, 1);
        assertEquals(new UserId(u1.value()), seen.get()); // committed detach, stale projected ATTACHED

        transactions.runInTransaction(() -> projection.apply(new CurrentBinding(e, new BindingRevision(2),
                CurrentBindingStatus.DETACHED, null, null, UUID.randomUUID(), Instant.now())));
        seen.set(null);
        read.read(e, authorities, pot, 1);
        assertNull(seen.get());

        var b2 = transactions.runInTransaction(() -> authority.acquire(e, u2)).bindingId();
        transactions.runInTransaction(() -> projection.apply(new CurrentBinding(e, new BindingRevision(3),
                CurrentBindingStatus.ATTACHED, u2, b2, UUID.randomUUID(), Instant.now())));
        read.read(e, authorities, pot, 1);
        assertEquals(new UserId(u2.value()), seen.get());
    }
}
