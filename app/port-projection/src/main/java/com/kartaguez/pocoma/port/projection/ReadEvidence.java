package com.kartaguez.pocoma.port.projection;
/** Result of comparing the READ effect and its availability against the prepared source evidence. */
public enum ReadEvidence {
    SATISFIED,
    MISSING,
    DIVERGENT,
    /** The current projection advanced beyond the version resolved during recovery preparation. */
    UNAVAILABLE
}
