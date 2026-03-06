package com.softropic.payam.security.exposed.event;


import com.softropic.payam.email.api.EmailTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record SendMailEvent(List<Long> userIds,
                            EmailTemplate emailTemplate,
                            LocalDateTime deadline,
                            Map<String, Object> data,
                            String sendId) {
}
