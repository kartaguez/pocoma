package com.kartaguez.pocoma.compose.web.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.mock.env.MockEnvironment;

class AdmissionHttpConfigurationTest {

    @Test
    void limitsReadCapacityPropertyAndDefaultToOne() {
        var env = new MockEnvironment();
        env.setProperty("pocoma.v3.http.admission.maximum", "4");
        env.setProperty("pocoma.v3.http.admission.minimum-idle", "1");
        assertEquals(1, AdmissionHttpConfiguration.limits(env).capacity());

        env.setProperty("pocoma.v3.http.admission.capacity", "4");
        var limits = AdmissionHttpConfiguration.limits(env);
        assertEquals(4, limits.capacity());

        var transport = new AdmissionHttpConfiguration().admissionTransport(env, Optional.empty());
        assertEquals(4, transport.availableCapacity());
    }

    @Test
    void distributionHttpWritePropertiesConfiguresEffectiveCapacityOfFour() throws Exception {
        Path propsPath = resolveDistributionProperties();
        assertTrue(Files.isRegularFile(propsPath), "Missing distribution properties: " + propsPath);

        var props = new Properties();
        try (var reader = Files.newBufferedReader(propsPath)) {
            props.load(reader);
        }

        assertEquals("4", props.getProperty("pocoma.v3.http.admission.capacity"));
        assertFalse(props.containsKey("pocoma.v3.http.admission.maximum"),
                "Obsolete key pocoma.v3.http.admission.maximum must be removed");
        assertFalse(props.containsKey("pocoma.v3.http.admission.minimum-idle"),
                "Obsolete key pocoma.v3.http.admission.minimum-idle must be removed");

        var env = new MockEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("http-write", props));
        assertEquals(4, AdmissionHttpConfiguration.limits(env).capacity());
    }

    private static Path resolveDistributionProperties() {
        for (Path candidate : new Path[] {
                Path.of("docker/v3-five-runtimes/http-write.properties"),
                Path.of("../docker/v3-five-runtimes/http-write.properties"),
                Path.of("../../docker/v3-five-runtimes/http-write.properties")
        }) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        throw new AssertionError("Could not locate docker/v3-five-runtimes/http-write.properties");
    }
}
