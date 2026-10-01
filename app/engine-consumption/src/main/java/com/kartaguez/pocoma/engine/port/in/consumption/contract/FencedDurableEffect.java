package com.kartaguez.pocoma.engine.port.in.consumption.contract;

@FunctionalInterface
public interface FencedDurableEffect {
	void apply();
}
