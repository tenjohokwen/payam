package com.softropic.payam.security.common.domain;

import java.time.LocalDateTime;

public interface LoginData {
    LocalDateTime getCreationDate();
    LocalDateTime getVerificationDate();
    LocalDateTime getTerminationDate();
    LocalDateTime getExpirationDate();
    String getToken();
    String getOtp();
    String getLoginId();
    String getClientId();
    String getIpAddress();
    String getRequestId();
    String getSqidSeed();
    String getSendId();
    String getSessionId();
}
