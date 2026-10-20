package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.*;
import jakarta.persistence.EntityManagerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Body guards apply to direct construction as well as proxied calls.
 * Ordered insert-only and unsupported-event rollback are exercised on PostgreSQL by V-D.
 */
class JpaPotCommandEventAppendAdapterTest {
    @Test void directWriterCannotAcceptEvenAnEmptyBatchWithoutItsOwner() {
        var emf = mock(EntityManagerFactory.class);
        var writer = new JpaPotCommandEventAppendAdapter(emf, new ObjectMapper());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> writer.appendAll(List.of()));
        verifyNoInteractions(emf);
    }
    @Test void appendRequiresTheWinningCallerTransaction() throws Exception {
        assertEquals(Propagation.MANDATORY, JpaPotCommandEventAppendAdapter.class.getMethod("appendAll", List.class)
                .getAnnotation(Transactional.class).propagation());
    }
}
