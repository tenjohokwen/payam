package com.softropic.payam.payment.ledger.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Value record for idempotency cache.
 * Serializes to/from a simple JSON payload: {"status":200,"body":"..."}
 */
public record CachedResponse(int httpStatus, String responseBody) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse from JSON string: {"status":200,"body":"..."}
     */
    public static CachedResponse fromJson(String json) {
        try {
            var node = MAPPER.readTree(json);
            int status = node.get("status").asInt();
            String body = node.get("body").asText();
            return new CachedResponse(status, body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse CachedResponse from JSON: " + json, e);
        }
    }

    /**
     * Serialize to JSON string: {"status":200,"body":"..."}
     */
    public static String toJson(int httpStatus, String responseBody) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("status", httpStatus);
            node.put("body", responseBody);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize CachedResponse to JSON", e);
        }
    }
}
