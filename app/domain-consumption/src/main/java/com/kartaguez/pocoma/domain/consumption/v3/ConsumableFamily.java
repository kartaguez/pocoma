package com.kartaguez.pocoma.domain.consumption.v3;

/** Durable source names: spelling is part of the routing wire format. */
public enum ConsumableFamily {
    COMMAND,
    IDENTITY_REQUEST,
    POT_EVENT,
    INVITATION_EVENT,
    IDENTITY_EVENT,
    COMMAND_COMPLETION_EVENT,
    IDENTITY_COMPLETION_EVENT
}
