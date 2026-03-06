package com.softropic.payam.email.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record Envelope(List<Recipient> recipients,
                       EmailTemplate emailTemplate,
                       LocalDateTime deadline,
                       Map<String, Object> data,
                       String sendId) {
}
