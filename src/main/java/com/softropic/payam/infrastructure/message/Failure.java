package com.softropic.payam.infrastructure.message;

public record Failure(String helpCode, String msgKey, String msg) implements Response {
}
