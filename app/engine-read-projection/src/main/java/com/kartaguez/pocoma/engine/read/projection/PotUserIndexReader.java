package com.kartaguez.pocoma.engine.read.projection;

public interface PotUserIndexReader {
	PotUserIndexPage findProjectedPots(PotUserIndexQuery query);
}
