package com.kartaguez.pocoma.engine.pot.command.decode;

import com.kartaguez.pocoma.engine.command.model.CommandType;

/** Stable identifiers for durable Pot Command payload contracts. */
public final class PotCommandTypes {

	public static final CommandType POT_CREATE_V1 = new CommandType("POT_CREATE_V1");
	public static final CommandType EXPENSE_CREATE_V1 = new CommandType("EXPENSE_CREATE_V1");
	public static final CommandType POT_SHAREHOLDERS_ADD_V1 = new CommandType("POT_SHAREHOLDERS_ADD_V1");
	public static final CommandType POT_DELETE_V1 = new CommandType("POT_DELETE_V1");
	public static final CommandType EXPENSE_DELETE_V1 = new CommandType("EXPENSE_DELETE_V1");
	public static final CommandType POT_DETAILS_UPDATE_V1 = new CommandType("POT_DETAILS_UPDATE_V1");
	public static final CommandType EXPENSE_DETAILS_UPDATE_V1 = new CommandType("EXPENSE_DETAILS_UPDATE_V1");
	public static final CommandType EXPENSE_SHARES_UPDATE_V1 = new CommandType("EXPENSE_SHARES_UPDATE_V1");
	public static final CommandType POT_SHAREHOLDERS_DETAILS_UPDATE_V1 =
			new CommandType("POT_SHAREHOLDERS_DETAILS_UPDATE_V1");
	public static final CommandType POT_SHAREHOLDERS_WEIGHTS_UPDATE_V1 =
			new CommandType("POT_SHAREHOLDERS_WEIGHTS_UPDATE_V1");

	private PotCommandTypes() {
	}
}
