package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.List;

public record AddPotShareholdersRequest(List<NewShareholderRequest> shareholders, long expectedVersion) {
}
