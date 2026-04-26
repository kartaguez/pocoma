package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.List;

public record UpdatePotShareholdersDetailsRequest(List<ShareholderDetailsRequest> shareholders, long expectedVersion) {
}
