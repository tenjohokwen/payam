package com.softropic.payam.common.message;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonSerialize
public record ErrorMsg(String errorKey, String message) {
}
