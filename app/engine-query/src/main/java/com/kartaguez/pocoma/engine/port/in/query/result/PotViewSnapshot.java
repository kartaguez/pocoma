package com.kartaguez.pocoma.engine.port.in.query.result;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;

public record PotViewSnapshot(PotHeaderSnapshot header, PotShareholdersSnapshot shareholders) {

	public PotViewSnapshot {
		Objects.requireNonNull(header, "header must not be null");
		Objects.requireNonNull(shareholders, "shareholders must not be null");
	}
}
