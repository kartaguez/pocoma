package com.kartaguez.pocoma.architecture.l7;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the actual Maven descriptors and bytecode, with no allowance for empty packages. */
class L7ReactorArchitectureTest {
    private static final String BASE="com.kartaguez.pocoma.";
    private static final Path APP=app();
    private static Path app() {
        Path cwd=Path.of("").toAbsolutePath();
        for(Path p=cwd;p!=null;p=p.getParent())if(Files.isDirectory(p.resolve("architecture-tests")) && Files.isRegularFile(p.resolve("pom.xml")))return p;
        throw new IllegalStateException("Maven reactor source root required");
    }
    private record Module(String name,Set<String> allowed) {}
    private static Map<String,Module> catalogue() throws Exception {
        var rows=Pattern.compile("^\\| (C\\d+) \\| `([^`]+)` \\| [^|]+ \\| [^|]+ \\| [^|]+ \\| ([^|]+) \\|",Pattern.MULTILINE)
            .matcher(Files.readString(APP.resolve("../docs/v3/L7_Target_Modular_Architecture_and_Migration_Plan.md")));
        var result=new LinkedHashMap<String,Module>();
        while(rows.find()) {
            var deps=new LinkedHashSet<String>();var ids=Pattern.compile("C\\d+").matcher(rows.group(3));
            if(!rows.group(1).equals("C38"))while(ids.find())deps.add(ids.group());
            assertNull(result.put(rows.group(1),new Module(rows.group(2),Set.copyOf(deps))));
        }
        assertEquals(42,result.size());return result;
    }
    private static Element pom(Path path) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    }
    private static List<Element> children(Element e,String name) {
        var result=new ArrayList<Element>();
        for(var n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element child && child.getTagName().equals(name))result.add(child);
        return result;
    }
    private static String value(Element e,String name){return children(e,name).stream().findFirst().map(Element::getTextContent).map(String::trim).orElse("");}
    private record Edge(String target,boolean test) {}
    private static List<Edge> internalEdges(Element pom,Map<String,String> idByName) {
        var edges=new ArrayList<Edge>();
        for(var deps:children(pom,"dependencies"))for(var dep:children(deps,"dependency")) {
            if(!value(dep,"groupId").equals("com.kartaguez.pocoma"))continue;
            String artifact=value(dep,"artifactId");String id=idByName.get(artifact);
            assertNotNull(id,"Unknown/retired reactor dependency: "+artifact);
            edges.add(new Edge(id,value(dep,"scope").equals("test")));
        }
        assertEquals(edges.size(),edges.stream().map(Edge::target).distinct().count(),"duplicate dependency");
        return edges;
    }
    @Test void exactCatalogueScopesAndAcyclicReactor() throws Exception {
        var cat=catalogue();var root=pom(APP.resolve("pom.xml"));assertEquals("pom",value(root,"packaging"));
        var actual=children(children(root,"modules").getFirst(),"module").stream().map(Element::getTextContent).map(String::trim).toList();
        var expected=cat.values().stream().map(Module::name).collect(java.util.stream.Collectors.toSet());
        assertEquals(42,actual.size());assertEquals(expected,Set.copyOf(actual));
        try(var dirs=Files.list(APP)){assertEquals(expected,dirs.filter(d->Files.isRegularFile(d.resolve("pom.xml"))).map(d->d.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));}
        var ids=new HashMap<String,String>();cat.forEach((id,m)->ids.put("pocoma-"+m.name(),id));
        var graph=new LinkedHashMap<String,List<Edge>>();
        for(var e:cat.entrySet()) {
            String id=e.getKey();Module m=e.getValue();var p=pom(APP.resolve(m.name()).resolve("pom.xml"));
            assertEquals("pocoma-"+m.name(),value(p,"artifactId"));
            var edges=internalEdges(p,ids);graph.put(id,edges);
            var productive=new HashSet<String>();for(var edge:edges)if(!edge.test())productive.add(edge.target());
            assertEquals(m.allowed(),productive,m.name());
            if(id.equals("C38"))assertEquals(41,edges.size(),"all implementation dependencies test-only");
        }
        for(String id:graph.keySet())visit(id,graph,new HashSet<>(),new HashSet<>());
        assertEquals(182,graph.values().stream().flatMap(Collection::stream).filter(e->!e.test()).count());
        assertForbidden(graph,"C36",Set.of("C11","C12","C13","C14","C27","C29","C30"));
        assertForbidden(graph,"C34",Set.of("C11","C12","C13","C14","C15","C16","C17","C21","C22","C27","C30","C31","C41"));
        assertForbidden(graph,"C35",Set.of("C15","C16","C17","C18","C19","C20","C28","C31","C32","C41","C43"));
    }
    private static void visit(String id,Map<String,List<Edge>> graph,Set<String> active,Set<String> done) {
        if(done.contains(id))return;
        assertTrue(active.add(id),"cycle through "+id+" in "+active);
        for(var e:graph.get(id))visit(e.target(),graph,active,done);
        active.remove(id);done.add(id);
    }
    private static void assertForbidden(Map<String,List<Edge>> graph,String root,Set<String> forbidden) {
        var queue=new ArrayDeque<String>();queue.add(root);var seen=new HashSet<String>();
        while(!queue.isEmpty()) {String current=queue.remove();if(!seen.add(current))continue;
            assertFalse(forbidden.contains(current),root+" reaches forbidden "+current);
            graph.get(current).stream().filter(e->!e.test()).map(Edge::target).forEach(queue::add);
        }
    }
    private static JavaClasses compiled(String module) {
        Path classes=APP.resolve(module).resolve("target/classes");
        assertTrue(Files.isDirectory(classes),"missing compiled module "+module);
        var result=new ClassFileImporter().importPath(classes);assertTrue(result.size()>0,"empty implementation module "+module);return result;
    }
    @Test void domainsAndContractsAreNeutralAndSuprasUseOnlyCapabilities() throws Exception {
        var cat=catalogue();var neutral=Set.of("C01","C02","C03","C04","C05","C06","C07","C08","C09","C39");
        for(String id:neutral)for(var cl:compiled(cat.get(id).name()))for(var dependency:cl.getDirectDependenciesFromSelf()) {
            String target=dependency.getTargetClass().getName();
            assertFalse(target.matches("(java\\.sql|javax\\.sql|org\\.springframework|jakarta\\.(servlet|persistence)|io\\.micrometer|com\\.zaxxer|com\\.fasterxml|com\\.kartaguez\\.pocoma\\.(engine|orchestrator|infra|compose|runtime|supra))\\..*"),dependency.toString());
        }
        for(String id:Set.of("C22","C23","C24"))for(var cl:compiled(cat.get(id).name()))for(var dependency:cl.getDirectDependenciesFromSelf()) {
            String target=dependency.getTargetClass().getName();
            assertFalse(target.startsWith(BASE+"engine.")||target.startsWith(BASE+"orchestrator.")||target.startsWith(BASE+"infra."),dependency.toString());
        }
        for(String id:Set.of("C11","C12","C13","C14","C15","C16","C17","C18","C19","C20","C43"))
            for(var cl:compiled(cat.get(id).name()))for(var dependency:cl.getDirectDependenciesFromSelf()) {
                String target=dependency.getTargetClass().getName();
                assertFalse(target.startsWith(BASE+"infra.")||target.startsWith(BASE+"runtime.")||target.startsWith(BASE+"compose.")||target.startsWith("org.springframework.")||target.startsWith("java.sql."),dependency.toString());
            }
    }
    @Test void sourceAdaptersCannotInvokeMutatorsAndRuntimesOnlyHostCompose() throws Exception {
        for(var cl:compiled("infra-postgres-write-source-read"))for(var d:cl.getDirectDependenciesFromSelf()) {
            String type=d.getTargetClass().getName();
            assertFalse(type.startsWith(BASE+"infra.postgres.write.")||type.startsWith(BASE+"engine.write."),d.toString());
            if(type.startsWith(BASE+"engine.materialize."))assertTrue(type.endsWith("Source"),d.toString());
            assertFalse(type.matches(".*\\.(JdbcConsumptionSlotStore|JdbcPartitionOwnershipStore|JdbcDiscoveryAdmissionStore)"),d.toString());
        }
        Path source=APP.resolve("infra-postgres-write-source-read/src/main/java");
        try(var files=Files.walk(source)) {
            var mutator=Pattern.compile("\\b(insert\\s+into|delete\\s+from|for\\s+update|executeUpdate|batchUpdate)\\b|\\b(?:sql|jdbc|named)\\.update\\(",Pattern.CASE_INSENSITIVE);
            for(var path:files.filter(p->p.toString().endsWith(".java")).toList())assertFalse(mutator.matcher(Files.readString(path)).find(),path.toString());
        }
        for(String mod:List.of("runtime-web-write","runtime-web-read","runtime-worker-process","runtime-worker-materialize","runtime-monolith")) {
            var classes=compiled(mod);assertEquals(1,classes.size(),mod+" must be a hosting root only");
            for(var cl:classes)for(var d:cl.getDirectDependenciesFromSelf()) {
                String t=d.getTargetClass().getName();
                if(t.startsWith(BASE)&&!t.equals(cl.getName()))assertTrue(t.startsWith(BASE+"compose."),d.toString());
            }
        }
    }
    @Test void migrationBytesAndResourceOwnershipAreConserved() throws Exception {
        var manifest=new ObjectMapper().readTree(Files.readString(APP.resolve("../docs/testing/evidence/L7-full-execution/sql-migration-ownership.json")));
        assertEquals(64,manifest.size());
        var seen=new HashSet<String>();
        for(var row:manifest) {
            String path=row.get("new").asText();assertTrue(seen.add(path),path);
            assertTrue(path.startsWith("infra-postgres-write/")||path.startsWith("infra-postgres-read/"),path);
            var bytes=Files.readAllBytes(APP.resolve(path));
            assertEquals(row.get("sha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),path);
        }
        var resources=new HashSet<String>();
        try(var files=Files.walk(APP)) {
            for(var p:files.filter(p->p.toString().contains("/src/main/resources/") && p.toString().endsWith(".sql")).toList())
                assertTrue(resources.add(p.toString().split("/src/main/resources/",2)[1]),"duplicated resource: "+p);
        }
    }
    @Test void v2RootsAndImplicitPersistenceActivationAreAbsent() throws Exception {
        for(String legacy:List.of(BASE+"PocomaWebApiApplication",BASE+"PocomaCommandConsumptionWorkerApplication",BASE+"PocomaTaskConsumptionWorkerApplication",BASE+"supra.polling.consumption.ConsumptionPollingWorker",BASE+"runtime.command.consumption.CommandConsumptionRuntimeConfiguration"))
            assertThrows(ClassNotFoundException.class,()->Class.forName(legacy,false,getClass().getClassLoader()),legacy);
        for(String mod:List.of("infra-postgres-write","infra-postgres-read","infra-postgres-core","infra-postgres-write-source-read")) {
            Path meta=APP.resolve(mod+"/src/main/resources/META-INF");
            assertFalse(Files.exists(meta.resolve("spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")));
            assertFalse(Files.exists(meta.resolve("spring.factories")));
        }
    }
}
