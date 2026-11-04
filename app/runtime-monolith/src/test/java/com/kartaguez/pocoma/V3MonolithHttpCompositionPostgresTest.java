package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.V3CompositionHttpFixture.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.engine.read.currentbinding.v3.*;
import com.kartaguez.pocoma.engine.read.commandresult.v3.*;
import com.kartaguez.pocoma.engine.read.registrationresult.v3.*;
import com.kartaguez.pocoma.supra.http.read.CurrentBindingController;
import com.kartaguez.pocoma.supra.http.read.v3.*;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
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
        assertThrows(IllegalStateException.class, () -> V3ConsumptionWorkerSettings.requested(invalidProfiles));
        initializeWorkerDatabase(PG);
        try (var fixture = new ReadMonolithI8Fixture(PG); var http = new V3CompositionHttpFixture(PG)) {
            fixture.select(List.of("MaterializeCurrentBinding"), false);
            var props = new LinkedHashMap<>(fixture.properties); props.putAll(http.properties);
            props.put("pocoma.v3.worker.read-limits.navigation-page", "100");
            var app = new SpringApplication(PocomaRuntimeMonolithApplication.class); app.setAdditionalProfiles("v3-read-worker", "v3-http-read"); app.setDefaultProperties(props);
            app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("i1-monolith-fixture", props)));
            var context = app.run(); fixture.contexts.add(context);
            try {
                var services = context.getBean(V3ReadFacadeComposition.Services.class);
                assertSame(services, context.getBean(ReadPotV3.Queries.class));
                assertEquals(Set.of("MaterializeCurrentBinding"), services.activeParticipants()); assertTrue(services.workerState().isPresent());
                assertEquals(4, context.getBeansOfType(HikariDataSource.class).size()); assertFalse(context.containsBean("v3HttpReadPool"));
                http.context = context; http.baseUrl = "http://127.0.0.1:" + ((org.springframework.boot.web.server.context.WebServerApplicationContext) context).getWebServer().getPort();
                fixture.read.update("insert into read_current_binding values (?,?,?,1,?,?)", http.issuer, "owner", UUID.randomUUID(), OWNER, TOKEN);
                for (int n = 1; n <= 205; n++) fixture.read.update("insert into read_user_pot values (?,?,1,true)", OWNER, id(10000+n));
                assertEquals(200, http.get("/me/binding").statusCode());
                var page = http.get("/me/pots?limit=100"); assertEquals(200, page.statusCode()); assertEquals(100, http.body(page).get("potIds").size());
                assertEquals(8, routes(http).size());
                assertEquals(1, context.getBeansOfType(V3ReadController.class).size()); assertEquals(1, context.getBeansOfType(V3ConsumptionWorker.class).size());
                assertTrue(context.getBeansOfType(CurrentBindingController.class).isEmpty());
                http.evidence("C02-monolith", Map.of("root", PocomaRuntimeMonolithApplication.class.getName(), "pools", 4, "httpSharesReadOwnerAndQuota", true, "participants", services.activeParticipants()));
                fixture.drain(context);
            } finally { context.close(); fixture.contexts.remove(context); }
        }
    }
}
