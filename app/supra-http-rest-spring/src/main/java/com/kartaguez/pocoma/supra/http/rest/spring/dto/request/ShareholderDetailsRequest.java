package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.UUID;

public record ShareholderDetailsRequest(UUID shareholderId, String name, UUID userId) {
}
