package com.softropic.payam.orange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;
import com.softropic.payam.orange.service.OrangeTimeUtil;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OrangeTimeUtilTest {

    @Test
    void parseOrangeTimestamp_converts_WAT_to_UTC() {
        // "2024-01-15T10:30:00" is WAT (UTC+1, Africa/Douala).
        // Expected UTC: subtract 1 hour => 2024-01-15T09:30:00Z
        Instant result = OrangeTimeUtil.parseOrangeTimestamp("2024-01-15T10:30:00");

        assertThat(result).isEqualTo(Instant.parse("2024-01-15T09:30:00Z"));
    }

    @Test
    void getCreatetimeAsInstant_returns_null_for_null_createtime() throws Exception {
        OrangeWebhookPayload payload = new ObjectMapper()
            .readValue("{\"payToken\":\"tok\",\"status\":\"PENDING\"}", OrangeWebhookPayload.class);

        assertThat(payload.getCreatetimeAsInstant()).isNull();
    }

    @Test
    void getCreatetimeAsInstant_returns_correct_instant() throws Exception {
        OrangeWebhookPayload payload = new ObjectMapper()
            .readValue("{\"createtime\":\"2024-01-15T10:30:00\"}", OrangeWebhookPayload.class);

        assertThat(payload.getCreatetimeAsInstant())
            .isEqualTo(Instant.parse("2024-01-15T09:30:00Z"));
    }
}
