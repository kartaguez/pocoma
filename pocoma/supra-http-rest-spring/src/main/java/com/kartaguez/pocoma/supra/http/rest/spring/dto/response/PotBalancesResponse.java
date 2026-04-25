package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

import java.util.List;

public record PotBalancesResponse(String potId, long version, List<BalanceResponse> balances) {
}
