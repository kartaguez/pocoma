package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

public record UserPotBalanceResponse(PotResponse pot, String shareholderId, BalanceResponse balance, long version) {
}
