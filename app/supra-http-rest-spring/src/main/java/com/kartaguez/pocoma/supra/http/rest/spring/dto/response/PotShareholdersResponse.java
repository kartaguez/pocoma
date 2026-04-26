package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

import java.util.List;

public record PotShareholdersResponse(String potId, List<ShareholderResponse> shareholders, long version) {
}
