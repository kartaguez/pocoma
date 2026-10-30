package com.kartaguez.pocoma.runtime.command.v3.dev;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

class V3WorkerDevelopmentToolsTest {
    @TempDir Path directory;

    Path file(String name, String content) throws Exception {
        var path = directory.resolve(name);
        Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, content);
        return path;
    }

    String input(String operation, String token) {
        return "request-id=abc00000-0000-4000-8000-000000000001\n"
                   + "created-at=2026-10-30T00:00:00Z\n"
                   + "operation="
                + operation
                + "\n"
                + "requester.issuer=controlled\n"
                + "requester.subject=a\n"
                + "target.issuer=controlled\n"
                + "target.subject=b\n"
                + "binding-token="
                + token
                + "\n";
    }

    @Test
    void l3_canonical_internal_intention() throws Exception {
        var first = file("first", input("LINK", "opaque-private-fixture"));
        var lines = new ArrayList<>(Files.readAllLines(first));
        Collections.reverse(lines);
        var second =
                file(
                        "second",
                        "# same logical intention, different bytes\n\n"
                                + String.join("\n", lines)
                                + "\n\n");
        assertFalse(
                Arrays.equals(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(first)),
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(second))));
        var a = V3WorkerDevelopmentTools.internalRequest(first);
        var b = V3WorkerDevelopmentTools.internalRequest(second);
        assertEquals(a, b, "layout must not change idempotent immutable provenance");
        assertEquals(IdentityRequest.Origin.CONTROLLED_INTERNAL, a.admission().origin());
        assertEquals(Map.of("scope", "internal-test-only"), a.admission().attestations());
        assertNotEquals(
                a.admission().intentionFingerprint(),
                V3WorkerDevelopmentTools.internalRequest(
                                file("token", input("LINK", "another-private-fixture")))
                        .admission()
                        .intentionFingerprint());
        assertNotEquals(
                a.admission().intentionFingerprint(),
                V3WorkerDevelopmentTools.internalRequest(
                                file("operation", input("UNLINK", "opaque-private-fixture")))
                        .admission()
                        .intentionFingerprint());
        assertFalse(a.toString().contains("opaque-private-fixture"));
    }

    @Test
    void l3_private_intention_files() throws Exception {
        var path = file("private", input("LINK", "opaque-private-fixture"));
        assertNotNull(V3WorkerDevelopmentTools.internalRequest(path));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(
                IllegalArgumentException.class,
                () -> V3WorkerDevelopmentTools.internalRequest(path));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        var link = directory.resolve("link");
        Files.createSymbolicLink(link, path);
        assertThrows(
                IllegalArgumentException.class,
                () -> V3WorkerDevelopmentTools.internalRequest(link));
    }
}
