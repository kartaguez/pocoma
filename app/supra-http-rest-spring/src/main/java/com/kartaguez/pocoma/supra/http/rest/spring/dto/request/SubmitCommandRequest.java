package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

public record SubmitCommandRequest(String commandType, Object payload) {
}
