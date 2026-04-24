package com.kartaguez.pocoma.domain.value.id;

import java.util.UUID;

public final class ShareholderId extends EntityId {

	public ShareholderId(UUID value) {
		super(value);
	}

	public static ShareholderId of(UUID value) {
		return new ShareholderId(value);
	}
}
