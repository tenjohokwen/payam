package com.softropic.payam.security.service;



import com.softropic.payam.common.ClockProvider;
import com.softropic.payam.security.common.domain.LoginData;
import com.softropic.payam.security.common.util.SecurityConstants;
import com.softropic.payam.security.exposed.exception.SecException;
import com.softropic.payam.security.exposed.util.RequestMetadata;
import com.softropic.payam.security.exposed.util.RequestMetadataProvider;
import com.softropic.payam.security.domain.LoginInfo;
import com.softropic.payam.security.repository.LoginInfoRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.softropic.payam.security.exposed.exception.SecurityError.CLIENT_INFO_MISMATCH;
import static com.softropic.payam.security.exposed.exception.SecurityError.INVALID_LOGIN_INFO_ID;
import static com.softropic.payam.security.exposed.exception.SecurityError.OTP_ALREADY_USED;
import static com.softropic.payam.security.exposed.exception.SecurityError.OTP_EXPIRED;
import static com.softropic.payam.security.exposed.exception.SecurityError.OTP_MISMATCH;


@Service
@Transactional
public class LoginInfoService {
    private final LoginInfoRepository loginInfoRepository;

    public LoginInfoService(LoginInfoRepository loginInfoRepository) {this.loginInfoRepository = loginInfoRepository;}

    public LoginInfo saveLoginInfo(String token, String otp, String loginId, String sqidSeed, String sendId) {
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        final LoginInfo loginInfo = new LoginInfo();
        loginInfo.setToken(token);
        loginInfo.setOtp(otp);
        loginInfo.setLoginId(loginId);
        loginInfo.setSqidSeed(sqidSeed);
        final LocalDateTime now = LocalDateTime.now(ClockProvider.getClock());
        loginInfo.setCreationDate(now);
        loginInfo.setExpirationDate(now.plusMinutes(SecurityConstants.OTP_TTL.toMinutes()));
        loginInfo.setRequestId(clientInfo.getRequestId());
        loginInfo.setClientId(clientInfo.getClientIdentifier());
        loginInfo.setIpAddress(clientInfo.getIpAddress());
        loginInfo.setSendId(sendId);
        loginInfo.setSessionId(clientInfo.getSessionId());
        return loginInfoRepository.save(loginInfo);
    }

    public LoginData fetchValidLoginData(Long loginInfoId, String otp) {
        final Optional<LoginData> loginDataOpt = loginInfoRepository.findOneById(loginInfoId);
        final Map<String, Object> ctx = Map.of("loginInfoId", loginInfoId,
                                               "otp", otp);
        //TODO test the scenarios
        if(loginDataOpt.isEmpty()) {
            //throw exception //throw exception indicating that no match was found. Also create an event and log (this endpoint could be misused
            throw new SecException("loginInfo could not be found in the database", ctx, INVALID_LOGIN_INFO_ID);
        }
        final LoginData loginData = loginDataOpt.get();
        if(!StringUtils.equals(loginData.getOtp(), otp)) {
            final Map<String, Object> ctxWithFetchedOtp = new HashMap<>(ctx);
            ctxWithFetchedOtp.put("fetchedOTP", loginData.getOtp());
            throw new SecException("otp of loginInfo does not match the expected otp",
                                   ctxWithFetchedOtp,
                                   OTP_MISMATCH);
        }
        if(loginData.getExpirationDate().isBefore(LocalDateTime.now(ClockProvider.getClock()))) {
            final Map<String, Object> ctxWithExpiration = new HashMap<>(ctx);
            ctxWithExpiration.put("expiration", loginData.getExpirationDate());
            throw new SecException("The validity of the otp has expired",
                                   ctxWithExpiration,
                                   OTP_EXPIRED);
        }
        final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();
        if(!StringUtils.equals(loginData.getClientId(), clientInfo.getClientIdentifier()) ||
                !StringUtils.equals(loginData.getSessionId(), clientInfo.getSessionId())) {
            //throw fraud alert
            final Map<String, Object> ctxWithClientId = new HashMap<>(ctx);
            ctxWithClientId.put("initial clientId", loginData.getClientId());
            ctxWithClientId.put("current clientId", clientInfo.getClientIdentifier());
            ctxWithClientId.put("initial sessionId", loginData.getSessionId());
            ctxWithClientId.put("current sessionId", clientInfo.getSessionId());
            throw new SecException("Check the client id and sessionId. They have to match. See context variables",
                                   ctxWithClientId,
                                   CLIENT_INFO_MISMATCH);
        }
        if(loginData.getVerificationDate() != null) {
            //
            throw new SecException("otp has already been used", ctx, OTP_ALREADY_USED);
        }
        return loginData;
    }

    public boolean markLoginInfoAsConsumed(Long loginInfoId) {
        //loginInfo is likely in the session so call whole object and update instead of making an update query
        return loginInfoRepository.findById(loginInfoId)
                                  .map(loginInfo -> {
                                      loginInfo.setVerificationDate(LocalDateTime.now(ClockProvider.getClock()));
                                      return loginInfo;
                                  }).isPresent();
    }


}
