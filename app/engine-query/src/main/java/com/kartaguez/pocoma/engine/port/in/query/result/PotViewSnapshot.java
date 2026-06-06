package com.kartaguez.pocoma.engine.port.in.query.result;

import java.util.Objects;

import com.kartaguez.pocoma.engine.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.PotShareholdersSnapshot;

public record PotViewSnapshot(PotHeaderSnapshot header, PotShareholdersSnapshot shareholders) {

	public PotViewSnapshot {
		Objects.requireNonNull(header, "header must not be null");
		Objects.requireNonNull(shareholders, "shareholders must not be null");
	}
}
