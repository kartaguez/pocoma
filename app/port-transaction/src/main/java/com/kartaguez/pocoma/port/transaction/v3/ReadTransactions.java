package com.kartaguez.pocoma.port.transaction.v3;
import java.util.function.Function;
/** Executes one bounded, coherent snapshot; contexts never escape the callback. */
public interface ReadTransactions { <T> T read(Function<RecoveryReadContext,T> action); }
