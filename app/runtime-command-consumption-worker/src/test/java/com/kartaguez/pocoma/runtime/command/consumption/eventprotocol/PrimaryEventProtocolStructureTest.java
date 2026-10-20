package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.asm.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.*;
import com.kartaguez.pocoma.runtime.command.consumption.eventprotocol.PrimaryEventProtocolTestConfiguration.*;

/** Bounded bytecode architecture rules inside COMMAND; no global architecture gate.
 * Mirrors the 4A ban on writer hooks for the new synthetic producer composition.
 * Deliberately does not claim to inspect arbitrary SQL or all possible native bypasses.
 */
class PrimaryEventProtocolStructureTest {
    static List<String> violations(Class<?> type, boolean terminal) throws IOException {
        List<String> failures = new ArrayList<>();
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean iface) {
                            if (Set.of("registerSynchronization", "runAfterCommit", "setSavepoint", "createSavepoint",
                                    "rollbackToSavepoint", "releaseSavepoint", "setAutoCommit", "commit", "rollback", "merge").contains(method)
                                    || owner.equals("org/springframework/transaction/support/TransactionTemplate")
                                    || owner.startsWith("java/net/"))
                                failures.add(name + " -> " + owner + "." + method);
                            if (terminal && (owner.startsWith("java/lang/reflect/")
                                    || owner.contains("/entity/") || owner.endsWith("SyntheticWriter")
                                    || Set.of("recordPublishedEvent", "requireOpen", "runInTransaction", "persist", "persistNew").contains(method)))
                                failures.add(name + " -> late business/publication access " + owner + "." + method);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return failures;
    }
    @Test void syntheticWriterHasNoCallbackCollectionMergeOrIndependentResolution() throws Exception {
        assertEquals(List.of(), violations(SyntheticWriter.class, false));
    }
    @Test void terminalProbeHasOnlyClosedValuesAndNoBusinessExtensionOrWriterCallback() throws Exception {
        assertEquals(List.of(), violations(SyntheticSuffix.class, true));
        assertEquals(List.of(ClosedPublishedEvents.class), List.of(PrimaryEventTransactionSuffix.class.getDeclaredMethods()[0].getParameterTypes()));
        for (Field field : SyntheticSuffix.class.getDeclaredFields()) {
            assertFalse(Runnable.class.isAssignableFrom(field.getType()));
            assertFalse(java.util.function.Supplier.class.isAssignableFrom(field.getType()));
            assertFalse(java.util.function.Consumer.class.isAssignableFrom(field.getType()));
        }
        assertTrue(java.lang.reflect.Modifier.isFinal(ClosedPublishedEvents.class.getModifiers()));
    }
    @Test void realSequencerHasNoBusinessCallbackOrIndependentResolution() throws Exception {
        assertEquals(List.of(), violations(JdbcEventTransactionSequencer.class, true));
        for (Field field : JdbcEventTransactionSequencer.class.getDeclaredFields()) {
            assertFalse(Runnable.class.isAssignableFrom(field.getType()));
            assertFalse(java.util.function.Supplier.class.isAssignableFrom(field.getType()));
            assertFalse(java.util.function.Consumer.class.isAssignableFrom(field.getType()));
        }
    }
    @Test void writerBeforeCompletionAndRogueLateMutationAreRejectedStructurallyBeforeInvocation() throws Exception {
        assertFalse(violations(ForbiddenWriterCallback.class, false).isEmpty());
        assertFalse(violations(ForbiddenLateMutation.class, true).isEmpty());
        // Spring can ignore a beforeCompletion exception: this callback is rejected, not executed.
        assertEquals(0, ForbiddenWriterCallback.invocations);
    }
    @Test void externalAcknowledgementCannotBeWiredAsAtomicDurableAcceptance() throws Exception {
        assertFalse(violations(ForbiddenExternalAcceptance.class, false).isEmpty());
        assertEquals(0, ForbiddenExternalAcceptance.sends, "reject composition before any send");
    }
    @Test void dormantTechnicalTypesHaveNoStereotypeConfigurationOrAutoconfigurationEntry() {
        for (Class<?> type : List.of(PrimaryEventTransactionRunner.class, PrimaryEventInsert.class, ClosedPublishedEvents.class, JdbcEventTransactionSequencer.class)) {
            assertEquals(0, type.getDeclaredAnnotations().length, type.getName());
        }
        assertNotNull(PrimaryEventProtocolTestConfiguration.class.getAnnotation(org.springframework.boot.test.context.TestConfiguration.class));
    }
    static final class ForbiddenWriterCallback {
        static int invocations;
        void publish(JdbcTemplate jdbc) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCompletion() {
                    invocations++; jdbc.update("insert into forbidden values (1)");
                }
            });
        }
    }
    static final class ForbiddenExternalAcceptance {
        static int sends;
        java.net.http.HttpClient client;
        void accept(java.net.http.HttpRequest request) throws Exception {
            sends++; client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
        }
    }
    static final class ForbiddenLateMutation implements PrimaryEventTransactionSuffix {
        Object entity; Field field;
        @Override public void execute(ClosedPublishedEvents closed) {
            try { field.set(entity, 7); } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
        }
    }
}
