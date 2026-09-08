package com.kartaguez.pocoma.engine.read.projection;

import java.util.function.Supplier;

public interface ReadStoreTransactionRunner {
	<T> T run(Supplier<T> action);
}
