package com.kartaguez.pocoma.infra.tx.spring.v3.fixture;

import java.util.UUID;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Generic test row; deliberately outside every production entity scan. */
@Entity
@Table(name = "foundation_effect")
public class FoundationEffect {
    @Id
    private UUID id;
    private String payload;

    protected FoundationEffect() {}

    public FoundationEffect(UUID id, String payload) {
        this.id = id;
        this.payload = payload;
    }
}
