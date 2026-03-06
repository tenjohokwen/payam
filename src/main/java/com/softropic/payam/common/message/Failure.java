package com.softropic.payam.common.message;

public record Failure(String helpCode, String msgKey, String msg) implements Response {
}
