package com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event;

import java.util.Objects;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;

/** Insert-only primitive for preassigned, new entity identities. Never merge/upsert.
 * No collection here. The certified writer returns its acceptance to orchestration.
 * Real outbox writers adopt this primitive only in step 4D.
 */
public final class PrimaryEventInsert {
    private final PrimaryEventTransactionRunner owner;
    private final EntityManagerFactory emf;

    public PrimaryEventInsert(PrimaryEventTransactionRunner owner) {
        this.owner = Objects.requireNonNull(owner);
        this.emf = owner.entityManagerFactory();
    }

    public void persistNew(Object entity) {
        owner.requireOpen();
        try {
            Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(emf)).persist(entity);
        } catch (RuntimeException | Error failure) {
            owner.poisonPublication();
            throw failure;
        }
    }
}
