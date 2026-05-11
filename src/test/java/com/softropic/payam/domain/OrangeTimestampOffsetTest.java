package com.softropic.payam.domain;

import com.softropic.payam.payment.provider.orange.service.OrangeTimeUtil;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MUT-02: Orange +01:00 (WAT) timestamp offset mutation kill.
 *
 * Pure unit test — no @SpringBootTest, no Testcontainers, starts in milliseconds.
 * PITest targetTests points to com.softropic.payam.domain.* — this test is a PITest target.
 *
 * Mirrors OrangeTimestampWatTest (e2e.domain.*) for PITest targeting.
 * OrangeTimeUtil.parseOrangeTimestamp() parses the timestamp as WAT (UTC+1, Africa/Douala),
 * not UTC. A mutation changing ZoneId to UTC would produce an Instant 3600s later.
 *
 * WAT = UTC+1, no DST. "2024-01-15T10:30:00" in WAT = "2024-01-15T09:30:00Z" in UTC.
 * UTC parse of "2024-01-15T10:30:00" produces "2024-01-15T10:30:00Z".
 * Difference = 3600 seconds.
 */
class OrangeTimestampOffsetTest {

    @Test
    void parseOrangeTimestamp_appliesWatOffset_notUtc() {
        String createtime = "2024-01-15T10:30:00";

        Instant watInstant = OrangeTimeUtil.parseOrangeTimestamp(createtime);

        Instant utcInstant = LocalDateTime.parse(createtime,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
            .toInstant(ZoneOffset.UTC);

        // WAT parse: 10:30 WAT = 09:30 UTC. UTC parse: 10:30 UTC.
        // Duration.between(watInstant, utcInstant) = utcInstant - watInstant = 3600s.
        // If mutation changes WAT to UTC: both parses produce same Instant → difference = 0s → FAILS.
        assertThat(Duration.between(watInstant, utcInstant).getSeconds())
            .as("WAT parse must differ from UTC parse by exactly 3600s " +
                "(WAT 10:30 = UTC 09:30, UTC parse 10:30 is 1h later)")
            .isEqualTo(3600L);
    }
}
