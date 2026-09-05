package com.kartaguez.pocoma.domain.authorization;

/** Canonical permissions currently defined by Pocoma. */
public final class PocomaPermissions {

	public static final Permission POT_VIEW = permission("POT", "VIEW");
	public static final Permission POT_CREATE = permission("POT", "CREATE");
	public static final Permission POT_UPDATE = permission("POT", "UPDATE");
	public static final Permission POT_DELETE = permission("POT", "DELETE");
	public static final Permission POT_VIEW_ARCHIVE = permission("POT", "VIEW_ARCHIVE");

	public static final Permission SHAREHOLDER_VIEW = permission("SHAREHOLDER", "VIEW");
	public static final Permission SHAREHOLDER_CREATE = permission("SHAREHOLDER", "CREATE");
	public static final Permission SHAREHOLDER_UPDATE = permission("SHAREHOLDER", "UPDATE");
	public static final Permission SHAREHOLDER_DELETE = permission("SHAREHOLDER", "DELETE");
	public static final Permission SHAREHOLDER_VIEW_ARCHIVE = permission("SHAREHOLDER", "VIEW_ARCHIVE");

	public static final Permission EXPENSE_VIEW = permission("EXPENSE", "VIEW");
	public static final Permission EXPENSE_CREATE = permission("EXPENSE", "CREATE");
	public static final Permission EXPENSE_UPDATE = permission("EXPENSE", "UPDATE");
	public static final Permission EXPENSE_DELETE = permission("EXPENSE", "DELETE");
	public static final Permission EXPENSE_VIEW_ARCHIVE = permission("EXPENSE", "VIEW_ARCHIVE");

	public static final Permission BALANCE_VIEW = permission("BALANCE", "VIEW");

	private PocomaPermissions() {
	}

	private static Permission permission(String objectType, String action) {
		return new Permission(objectType, action);
	}
}
