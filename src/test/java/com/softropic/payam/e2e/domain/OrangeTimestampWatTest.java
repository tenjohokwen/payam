package com.softropic.payam.e2e.domain;

import com.softropic.payam.orange.service.OrangeTimeUtil;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INV-10-TEST + MUT-02 Orange timestamp mutation kill.
 *
 * Pure unit test — no Spring context, no @SpringBootTest.
 * Verifies that OrangeTimeUtil.parseOrangeTimestamp() parses the timestamp as WAT (UTC+1),
 * not UTC. The mutation (changing ZoneId "Africa/Douala" to UTC) would produce an Instant
 * 3600s later, killing this test.
 *
 * WAT = UTC+1, no DST. "2024-01-15T10:30:00" in WAT = "2024-01-15T09:30:00Z" in UTC.
 * UTC parse of "2024-01-15T10:30:00" produces "2024-01-15T10:30:00Z".
 * Difference = 3600 seconds.
 */
public class OrangeTimestampWatTest {

    @Test
    void parseOrangeTimestamp_returnsWatInstant_notUtcInstant() {
        String createtime = "2024-01-15T10:30:00";

        Instant watInstant = OrangeTimeUtil.parseOrangeTimestamp(createtime);

        Instant utcInstant = LocalDateTime.parse(createtime,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
            .toInstant(ZoneOffset.UTC);

        // WAT = UTC+1: WAT 10:30 = UTC 09:30. UTC parse of "10:30:00" gives UTC 10:30.
        // Duration.between(watInstant, utcInstant) = utcInstant - watInstant = 3600s.
        assertThat(Duration.between(watInstant, utcInstant).getSeconds())
            .as("WAT parse must differ from UTC parse by exactly 3600s " +
                "(WAT 10:30 = UTC 09:30, UTC parse 10:30 is 1h later)")
            .isEqualTo(3600L);
    }
}
