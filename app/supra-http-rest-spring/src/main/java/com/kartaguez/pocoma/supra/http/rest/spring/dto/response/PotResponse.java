package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

public record PotResponse(String id, String label, String creatorId, boolean deleted, long version) {
}
