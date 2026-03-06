package com.softropic.payam.security.audit;



import com.softropic.payam.security.exposed.util.SecurityUtil;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.Optional;


/**
 * Implementation of AuditorAware based on Spring Security.
 */
@Slf4j
@Component(SpringSecurityAuditorAware.NAME)
public class SpringSecurityAuditorAware implements AuditorAware<String> {

    public static final String NAME = "springSecurityAuditorAware";

    private final SecurityUtil securityUtil;

    public SpringSecurityAuditorAware(SecurityUtil securityUtil) {this.securityUtil = securityUtil;}

    @Override
    public Optional<String> getCurrentAuditor() {
        log.info("################# Audit current user name");
        String userName = securityUtil.getCurrentUserName();
        return Optional.of(userName != null ? userName : "SYSTEM_ACCOUNT");
    }
}
