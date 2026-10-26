package com.kartaguez.pocoma.infra.tx.spring.v3.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Generic test row; deliberately outside every production entity scan. */
@Entity
@Table(name = "foundation_effect")
public class FoundationEffect {
    @Id private UUID id;
    private String payload;

    public void setPayload(String payload) {
        this.payload = payload;
    }

    protected FoundationEffect() {}

    public FoundationEffect(UUID id, String payload) {
        this.id = id;
        this.payload = payload;
    }
}
