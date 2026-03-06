package com.softropic.payam.email.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Envelope(List<Recipient> recipients,
                       EmailTemplate emailTemplate,
                       Instant deadline,
                       Map<String, Object> data,
                       String sendId) {
}
