package com.kartaguez.pocoma;
import com.kartaguez.pocoma.contract.readquery.IdentityResultRead;
import com.kartaguez.pocoma.contract.readquery.CommandResultRead;
import com.kartaguez.pocoma.contract.readquery.BindingRead;
import com.kartaguez.pocoma.contract.readquery.PotRead;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.V3ReadHttpFixture.*;
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
        try (var f = new V3ReadHttpFixture(PG)) {
            f.start(PocomaWebApiApplication.class); f.corpus();
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
            assertEquals(expectedRoutes, routes(f));
            assertEquals(10, routes(f).size());
            assertTrue(routes(f).stream().allMatch(p -> p.startsWith("/api/v3/")));
            assertEquals(1, f.context.getBeansOfType(HikariDataSource.class).size()); assertEquals(2, f.pool().getMaximumPoolSize());
            assertTrue(f.context.getBeansOfType(V3ConsumptionWorker.class).isEmpty());
            assertTrue(f.context.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class).isEmpty(), "no implicit JDBC/JPA owner");
            assertTrue(f.context.getBeansOfType(jakarta.persistence.EntityManagerFactory.class).isEmpty(), "no implicit legacy JPA assembly");
            assertEquals(1, f.context.getBeansOfType(PotRead.Queries.class).size()); assertEquals(1, f.context.getBeansOfType(BindingRead.Query.class).size());
            assertEquals(200, f.get("/pots/" + POT).statusCode()); assertEquals(200, f.get("/me/binding").statusCode());
            assertTrue(f.context.getBeansOfType(com.kartaguez.pocoma.engine.admit.command.SubmitRecordedCommandService.class).isEmpty());
            f.evidence("C01-root", Map.of("root", PocomaWebApiApplication.class.getName(), "routes", routes(f), "pools", 1, "poolMaximum", 2, "workers", 0));
        }
    }
    static Set<String> routes(V3ReadHttpFixture f) {
        var routes = new TreeSet<String>(); f.context.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(mapping -> mapping.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType().getName().startsWith("com.kartaguez.pocoma.supra.")) routes.addAll(info.getPatternValues()); })); return routes;
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false) static class LegacyInjection {
        @Bean CurrentBindingController injectedLegacyController() { throw new AssertionError("preflight must refuse before bean creation"); }
    }
    @Test void legacyComponentsRejected() throws Exception {
        try (var f = new V3ReadHttpFixture(PG)) {
            var failure = assertThrows(Exception.class, () -> f.start(PocomaWebApiApplication.class, LegacyInjection.class));
            Throwable root = failure; while (root.getCause() != null) root = root.getCause();
            assertTrue(root.getMessage().contains("legacy HTTP/admission component refused in V3"), root.toString());
        }
    }
    @Test void readOnlyAuthorityAndNoWriteFallback() throws Exception {
        try (var f = new V3ReadHttpFixture(PG)) {
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
        for (Class<?> port : List.of(PotRead.Queries.class, BindingRead.Query.class, CommandResultRead.Query.class, IdentityResultRead.Query.class)) for (var method : port.getMethods()) for (Class<?> parameter : method.getParameterTypes()) assertFalse(parameter.getName().contains("Context"), "no owner/context crosses HTTP");
        String monolithPom = Files.readString(app.resolve("runtime-monolith/pom.xml")); assertFalse(monolithPom.contains("pocoma-runtime-web-api"), "Monolith must not depend on the Web runtime, even for tests");
    }
    static void visit(String node, Map<String, Set<String>> graph, Set<String> visiting, Set<String> done) {
        if (done.contains(node)) return; assertTrue(visiting.add(node), "Maven cycle " + node);
        for (String next : graph.getOrDefault(node, Set.of())) visit(next, graph, visiting, done); visiting.remove(node); done.add(node);
    }
    @Test void brunoFixtureProvenance() throws Exception {
        try (var f = new V3ReadHttpFixture(PG)) {
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
