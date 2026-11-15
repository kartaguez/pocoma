package com.kartaguez.pocoma.infra.postgres.fixture.command;

import jakarta.persistence.*;

@Entity
@Table(name = "v3_command_probe")
public class CommandProbe {
    @Id public long id;

    @Column(nullable = false)
    public String value;

    public CommandProbe() {}

    public CommandProbe(long id, String value) {
        this.id = id;
        this.value = value;
    }
}
