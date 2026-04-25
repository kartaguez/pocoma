package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.List;

public record UpdatePotShareholdersWeightsRequest(List<ShareholderWeightRequest> shareholders, long expectedVersion) {
}
