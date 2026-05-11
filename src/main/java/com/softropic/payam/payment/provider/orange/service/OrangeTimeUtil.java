package com.softropic.payam.payment.provider.orange.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class OrangeTimeUtil {

    // Cameroon = WAT = UTC+1, no DST. ZoneId "Africa/Douala" is the correct identifier.
    public static final ZoneId WAT = ZoneId.of("Africa/Douala");
    private static final DateTimeFormatter ORANGE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private OrangeTimeUtil() {}

    /**
     * Parse an Orange createtime string (no timezone offset, WAT assumed) to UTC Instant.
     * Never call LocalDateTime.parse() on Orange timestamps without this method — P5.1.
     *
     * @param createtime e.g. "2024-01-15T10:30:00"
     * @return UTC Instant
     */
    public static Instant parseOrangeTimestamp(String createtime) {
        return LocalDateTime.parse(createtime, ORANGE_FMT)
                            .atZone(WAT)
                            .toInstant();
    }
}
