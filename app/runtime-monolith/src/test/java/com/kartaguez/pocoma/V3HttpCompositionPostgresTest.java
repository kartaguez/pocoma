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
class V3HttpCompositionPostgresTest {
    @Container static final PostgreSQLContainer PG = container();
    @Test void webRoot() throws Exception {
        try (var f = new V3CompositionHttpFixture(PG)) {
            f.start(PocomaWebApiApplication.class); f.corpus();
            assertEquals(8, routes(f).size()); assertTrue(routes(f).stream().allMatch(p -> p.startsWith("/api/v3/")));
            assertEquals(1, f.context.getBeansOfType(HikariDataSource.class).size()); assertEquals(2, f.pool().getMaximumPoolSize());
            assertTrue(f.context.getBeansOfType(V3ConsumptionWorker.class).isEmpty());
            assertEquals(1, f.context.getBeansOfType(ReadPotV3.Queries.class).size()); assertEquals(1, f.context.getBeansOfType(GetCurrentBindingV3.Query.class).size());
            assertEquals(200, f.get("/pots/" + POT).statusCode()); assertEquals(200, f.get("/me/binding").statusCode());
            assertTrue(f.context.getBeansOfType(com.kartaguez.pocoma.engine.admit.command.SubmitRecordedCommandService.class).isEmpty());
            f.evidence("C01-root", Map.of("root", PocomaWebApiApplication.class.getName(), "routes", routes(f), "pools", 1, "poolMaximum", 2, "workers", 0));
        }
    }
    static Set<String> routes(V3CompositionHttpFixture f) {
        var routes = new TreeSet<String>(); f.context.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(mapping -> mapping.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType().getName().startsWith("com.kartaguez.pocoma.supra.")) routes.addAll(info.getPatternValues()); })); return routes;
    }
    @Test void monolithRoot() throws Exception {
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
    @Configuration(proxyBeanMethods = false) static class LegacyInjection {
        @Bean CurrentBindingController injectedLegacyController() { throw new AssertionError("preflight must refuse before bean creation"); }
    }
    @Test void legacyComponentsRejected() throws Exception {
        try (var f = new V3CompositionHttpFixture(PG)) {
            var failure = assertThrows(Exception.class, () -> f.start(PocomaWebApiApplication.class, LegacyInjection.class));
            Throwable root = failure; while (root.getCause() != null) root = root.getCause();
            assertTrue(root.getMessage().contains("legacy HTTP/admission component refused in V3"), root.toString());
        }
    }
    @Test void readOnlyAuthorityAndNoWriteFallback() throws Exception {
        try (var f = new V3CompositionHttpFixture(PG)) {
            f.start(PocomaWebApiApplication.class); f.seed(POT, 1, 0, Set.of(OWNER), false, true, true, "read-only"); f.results();
            assertEquals(200, f.get("/pots/" + POT).statusCode()); assertEquals(404, f.get("/pots/" + id(999)).statusCode());
            assertEquals(200, f.get("/commands/" + id(401) + "/result").statusCode());
            try (var connection = f.pool().getConnection()) {
                connection.setReadOnly(false);
                SQLException refused = assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate("insert into read_command_result values ('00000000-0000-0000-0000-000000009999','CreatePot','i1','i1','00000000-0000-0000-0000-000000000300','CHANGED',1,null)"));
                assertEquals("42501", refused.getSQLState());
            }
            assertEquals(0, f.admin.queryForObject("select count(*) from pg_stat_statements where userid=(select oid from pg_roles where rolname=?) and query ~* '^(insert|update|delete)'", Integer.class, f.role));
            assertEquals(1, f.context.getBeansOfType(HikariDataSource.class).size());
            f.evidence("C04-authority", Map.of("httpOnlyReadPool", true, "writeMutationRefusedSqlState", "42501", "readMissing", 404, "successfulWriteStatements", 0));
        }
    }
    @Test void typedPortsAndDependencies() throws Exception {
        Path app = Path.of("..").toAbsolutePath().normalize(); var poms = Files.list(app).filter(p -> Files.isRegularFile(p.resolve("pom.xml"))).sorted().toList(); assertEquals(60, poms.size());
        var graph = new HashMap<String, Set<String>>();
        for (Path module : poms) {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(module.resolve("pom.xml").toFile()); var ids = doc.getElementsByTagName("artifactId");
            String name = "pocoma-" + module.getFileName(); Set<String> edges = new HashSet<>();
            for (int n = 0; n < ids.getLength(); n++) if (ids.item(n).getParentNode().getNodeName().equals("dependency") && ids.item(n).getTextContent().startsWith("pocoma-")) edges.add(ids.item(n).getTextContent()); graph.put(name, edges);
        }
        for (String node : graph.keySet()) visit(node, graph, new HashSet<>(), new HashSet<>());
        String httpPom = Files.readString(app.resolve("supra-http-read/pom.xml")); assertFalse(httpPom.contains("composition-runtime-spring")); assertFalse(httpPom.contains("engine-write"));
        for (var file : Files.walk(app.resolve("supra-http-read/src/main/java/com/kartaguez/pocoma/supra/http/read/v3")).filter(p -> p.toString().endsWith(".java")).toList()) {
            String source = Files.readString(file); for (String forbidden : List.of("com.kartaguez.pocoma.runtime.", "com.kartaguez.pocoma.infra.", "engine.write", "engine.admit", "javax.sql", "RecoveryReadContext")) assertFalse(source.contains(forbidden), file + " " + forbidden);
        }
        for (Class<?> port : List.of(ReadPotV3.Queries.class, GetCurrentBindingV3.Query.class, GetCommandTerminalResult.Query.class, GetIdentityTerminalResult.Query.class)) for (var method : port.getMethods()) for (Class<?> parameter : method.getParameterTypes()) assertFalse(parameter.getName().contains("Context"), "no owner/context crosses HTTP");
        String monolithPom = Files.readString(app.resolve("runtime-monolith/pom.xml")); assertTrue(monolithPom.contains("<artifactId>pocoma-runtime-web-api</artifactId><version>${project.version}</version><scope>test</scope>"));
    }
    static void visit(String node, Map<String, Set<String>> graph, Set<String> visiting, Set<String> done) {
        if (done.contains(node)) return; assertTrue(visiting.add(node), "Maven cycle " + node);
        for (String next : graph.getOrDefault(node, Set.of())) visit(next, graph, visiting, done); visiting.remove(node); done.add(node);
    }
    @Test void brunoFixtureProvenance() throws Exception {
        try (var f = new V3CompositionHttpFixture(PG)) {
            f.start(PocomaWebApiApplication.class); f.corpus();
            var result = f.admin.queryForMap("select operation,requester_issuer,requester_subject,pot_id,write_outcome,resulting_pot_version from read_command_result where request_id=?", id(401));
            assertEquals("CreatePot", result.get("operation")); assertEquals(f.issuer, result.get("requester_issuer")); assertEquals("owner", result.get("requester_subject")); assertEquals(POT, result.get("pot_id")); assertEquals("CHANGED", result.get("write_outcome")); assertEquals(2L, result.get("resulting_pot_version"));
            assertEquals(205, f.admin.queryForObject("select count(*) from read_user_pot where user_id=?", Integer.class, OWNER));
            assertEquals(200, f.get("/commands/" + id(401) + "/result").statusCode());
            assertEquals(404, f.request("GET", "/commands/" + id(401) + "/result", f.jwt("same"), null, null, Map.of()).statusCode());
            assertFalse(f.context.containsBean("injector")); assertTrue(f.context.getBeansOfType(com.kartaguez.pocoma.engine.admit.registration.AdmitRegistrationService.class).isEmpty());
            f.evidence("C06-fixture", Map.of("provenance", "READ_FIXTURE — NO PUBLIC ADMISSION PROOF", "commandOutcome", "COMPLETED", "resultVersion", 2, "navigationRows", 205, "sameUserOtherEui", 404));
        }
    }
}
