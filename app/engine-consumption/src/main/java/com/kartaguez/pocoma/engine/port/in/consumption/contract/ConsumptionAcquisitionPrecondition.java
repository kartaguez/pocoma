package com.kartaguez.pocoma.engine.port.in.consumption.contract;

@FunctionalInterface
public interface ConsumptionAcquisitionPrecondition {
	boolean lockAndCheck();

	static ConsumptionAcquisitionPrecondition alwaysSatisfied() {
		return () -> true;
	}
}
