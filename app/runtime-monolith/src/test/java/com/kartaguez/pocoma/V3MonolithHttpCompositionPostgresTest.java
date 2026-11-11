package com.kartaguez.pocoma;
import com.kartaguez.pocoma.compose.polling.process.v3.ProcessSettings;
import com.kartaguez.pocoma.compose.web.read.v3.V3ReadFacadeComposition;
import com.kartaguez.pocoma.compose.polling.materialize.v3.MaterializeComposeConfiguration;
import com.kartaguez.pocoma.contract.readquery.PotRead;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.V3CompositionHttpFixture.*;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.engine.read.identity.v3.*;
import com.kartaguez.pocoma.engine.read.results.v3.*;
import com.kartaguez.pocoma.engine.read.results.v3.*;
import com.kartaguez.pocoma.supra.web.read.v3.*;
import com.kartaguez.pocoma.supra.polling.consumption.v3.V3ConsumptionWorker;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

@Testcontainers
class V3MonolithHttpCompositionPostgresTest {
    @Container static final PostgreSQLContainer PG = container();
    static Set<String> routes(V3CompositionHttpFixture f) {
        var routes = new TreeSet<String>(); f.context.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(mapping -> mapping.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType().getName().startsWith("com.kartaguez.pocoma.supra.")) routes.addAll(info.getPatternValues()); })); return routes;
    }
    @Test void monolithRoot() throws Exception {
        var invalidProfiles = new org.springframework.mock.env.MockEnvironment();
        invalidProfiles.setActiveProfiles("v3-read-worker", "v3-http-read", "unexpected");
        assertThrows(IllegalStateException.class, () -> ProcessSettings.requested(invalidProfiles));
        initializeWorkerDatabase(PG);
        try (var fixture = new ReadMonolithI8Fixture(PG); var http = new V3CompositionHttpFixture(PG)) {
            fixture.select(List.of("MaterializeCurrentBinding"), false);
            http.useMaterializedRead(fixture);
            var props = fixture.compositionProperties(http.properties);
            var app = new SpringApplication(PocomaRuntimeMonolithApplication.class); app.setAdditionalProfiles("v3-read-worker", "v3-http-read"); app.setDefaultProperties(props);
            app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("i1-monolith-fixture", props)));
            var context = app.run(); fixture.contexts.add(context);
            try {
                var services = context.getBean(V3ReadFacadeComposition.Services.class);
                assertSame(services, context.getBean(PotRead.Queries.class));
                var materialize = context.getBean(MaterializeComposeConfiguration.Resources.class);
                assertTrue(materialize.workerState().isPresent());
                var queryPool = context.getBean("v3HttpReadPool", HikariDataSource.class);
                var statusPool = context.getBean("v3HttpReadMaintenancePool", HikariDataSource.class);
                assertEquals(Set.of(queryPool, statusPool), Set.copyOf(context.getBeansOfType(HikariDataSource.class).values()));
                assertNotSame(materialize.readBusiness, queryPool);
                assertEquals(2, queryPool.getMaximumPoolSize());
                assertEquals(1, statusPool.getMaximumPoolSize());
                http.context = context; http.baseUrl = "http://127.0.0.1:" + ((org.springframework.boot.web.server.context.WebServerApplicationContext) context).getWebServer().getPort();
                fixture.read.update("insert into read_current_binding values (?,?,?,1,?,?)", http.issuer, "owner", UUID.randomUUID(), OWNER, TOKEN);
                for (int n = 1; n <= 205; n++) fixture.read.update("insert into read_user_pot values (?,?,1,true)", OWNER, id(10000+n));
                assertEquals(200, http.get("/me/binding").statusCode());
                var page = http.get("/me/pots?limit=100"); assertEquals(200, page.statusCode()); assertEquals(100, http.body(page).get("potIds").size());
                // Actual HTTP mode must retain its local SQL deadline on the query owner.
                try (var gate = fixture.read.getDataSource().getConnection()) {
                    gate.setAutoCommit(false);
                    gate.createStatement().execute("lock table read_current_binding in access exclusive mode");
                    long began = System.nanoTime();
                    assertEquals(503, http.get("/me/binding").statusCode());
                    double seconds = (System.nanoTime()-began)/1e9;
                    assertTrue(seconds >= 1.5 && seconds < 5, "HTTP SQL deadline remains bounded: " + seconds);
                    gate.rollback();
                }
                assertEquals(200, http.get("/me/binding").statusCode());
                var expectedRoutes =
                        Set.of(
                                "/api/v3/me/binding",
                                "/api/v3/pots/{potId}",
                                "/api/v3/pots/{potId}/versions/{version}",
                                "/api/v3/pots/{potId}/versions/{version}/balance",
                                "/api/v3/pots/{potId}/versions/{version}/expenses/query",
                                "/api/v3/me/pots",
                                "/api/v3/commands/{requestId}/result",
                                "/api/v3/identity/requests/{requestId}/result",
                                "/api/v3/invitations/by-creation-request/{requestId}",
                                "/api/v3/invitations/available-shareholders/query");
                assertEquals(expectedRoutes, routes(http));
                assertEquals(10, routes(http).size());
                assertEquals(1, context.getBeansOfType(V3ReadController.class).size());
                assertEquals(1, context.getBeansOfType(V3InvitationController.class).size());
                assertEquals(1, context.getBeansOfType(V3ConsumptionWorker.class).size());
                assertThrows(ClassNotFoundException.class, () -> Class.forName("com.kartaguez.pocoma.supra.web.read.CurrentBindingController"));
                http.evidence("C02-monolith", Map.of("root", PocomaRuntimeMonolithApplication.class.getName(), "httpPools", 2, "materializePools", 3, "httpQueryPermits", 2));
                fixture.drain(context);
            } finally { context.close(); fixture.contexts.remove(context); }
        }
    }
}
