package com.kartaguez.pocoma.architecture.l7;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class L7DeploymentArchitectureTest {
    private static Path root() {
        for(Path p=Path.of("").toAbsolutePath();p!=null;p=p.getParent())if(Files.isRegularFile(p.resolve("docker-compose.v3-five-runtimes.yml")))return p;
        throw new IllegalStateException("L7 deployment source required");
    }
    @SuppressWarnings("unchecked")
    @Test void fiveProcessesSelectFourActualArtifactsAndSeparatePrincipals() throws Exception {
        var root=root();var yaml=new YamlMapFactoryBean();yaml.setResources(new FileSystemResource(root.resolve("docker-compose.v3-five-runtimes.yml")));
        var services=(Map<String,Map<String,Object>>)Objects.requireNonNull(yaml.getObject()).get("services");
        var runtimes=Map.of("pocoma-http-write","runtime-web-write","pocoma-http-read","runtime-web-read",
            "pocoma-command-worker","runtime-worker-process","pocoma-results-worker","runtime-worker-materialize","pocoma-business-read-worker","runtime-worker-materialize");
        for(var expected:runtimes.entrySet()) {
            var service=services.get(expected.getKey());assertNotNull(service);
            var build=(Map<String,Object>)service.get("build");assertNotNull(build);
            var args=(Map<String,String>)build.get("args");assertEquals(expected.getValue(),args.get("RUNTIME_MODULE"));
            assertEquals("pocoma-"+expected.getValue(),args.get("RUNTIME_ARTIFACT"));
            assertFalse(service.get("volumes").toString().contains("pocoma-runtime.jar"),"image cannot be replaced by another Runtime's jar");
        }
        var config=root.resolve("docker/v3-five-runtimes");
        for(String name:List.of("results-worker","business-read-worker")) {
            var p=props(config.resolve(name+".properties"));
            assertEquals("pocoma_write_source_read",p.getProperty("pocoma.v3.worker.write-source-read.username"));
            assertTrue(p.stringPropertyNames().stream().noneMatch(k->k.contains("write-business")||k.contains("write-control")||k.equals("pocoma.v3.pilot.write.password")));
        }
        var process=props(config.resolve("command-worker.properties"));
        assertTrue(process.stringPropertyNames().stream().noneMatch(k->k.startsWith("pocoma.v3.pilot.read.")||k.contains("read-business")||k.contains("read-control")));
        var read=props(config.resolve("http-read.properties"));
        assertNotEquals(read.getProperty("pocoma.v3.http.read.username"),read.getProperty("pocoma.v3.http.read.maintenance.username"));
        assertNotEquals(read.getProperty("pocoma.v3.http.read.username"),props(config.resolve("results-worker.properties")).getProperty("pocoma.v3.worker.read-business.username"));
        assertFalse(Files.exists(root.resolve("docker-compose.distributed.yml")));
        assertFalse(Files.exists(root.resolve("docker-compose.functional.yml")));
    }
    private static Properties props(Path p)throws Exception {var result=new Properties();try(var in=Files.newInputStream(p)){result.load(in);}return result;}
}
