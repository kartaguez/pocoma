package com.kartaguez.pocoma.port.transaction;

/** A read snapshot capability, valid only in its owner's callback and thread. */
public interface RecoveryReadContext {
    void requireActiveRead();
}
