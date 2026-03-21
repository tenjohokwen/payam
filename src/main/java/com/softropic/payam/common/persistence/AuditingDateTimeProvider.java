package com.softropic.payam.common.persistence;




import com.softropic.payam.common.ClockProvider;

import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;


@Component(AuditingDateTimeProvider.NAME)
public class AuditingDateTimeProvider implements DateTimeProvider {

    public static final String NAME = "dateTimeProvider";

    @Override
    public Optional<TemporalAccessor> getNow() {
        final LocalDateTime localDateTime = LocalDateTime.now(ClockProvider.getClock());
        return Optional.of(localDateTime);
    }

}
