package com.softropic.payam.disbursement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuration properties bound from the {@code payam.disbursement} YAML block.
 *
 * <p>Registered via {@link DisbursementConfig} which uses
 * {@code @EnableConfigurationProperties(DisbursementProperties.class)} — same pattern as
 * {@link com.softropic.payam.platform.admin.config.PayamPlatformProperties}.
 *
 * <p>YAML binding example:
 * <pre>
 * payam:
 *   disbursement:
 *     admin-approval-threshold: 5000000
 *     admin-approval-timeout-hours: 24
 * </pre>
 *
 * <p><strong>Threshold ordering invariant:</strong> {@code adminApprovalThreshold} MUST exceed
 * the merchant step-up threshold (500,000 XAF, hardcoded in DisbursementOrchestrator)
 * so the two gates remain co-existent. Default 5,000,000 XAF (10x the step-up).
 */
@ConfigurationProperties(prefix = "payam.disbursement")
public class DisbursementProperties {

    /** ADMIN-01: amounts strictly greater than this threshold route to PENDING_ADMIN_APPROVAL. */
    private BigDecimal adminApprovalThreshold = BigDecimal.valueOf(5_000_000);

    /** ADMIN-03: hours after which a PENDING_ADMIN_APPROVAL disbursement auto-expires. */
    private int adminApprovalTimeoutHours = 24;

    /**
     * Cron expression for the admin-approval expiry Quartz job (Plan 03).
     * Default: every minute on the second.
     */
    private String adminApprovalExpiryCron = "0 * * * * ?";

    public BigDecimal getAdminApprovalThreshold() {
        return adminApprovalThreshold;
    }

    public void setAdminApprovalThreshold(BigDecimal adminApprovalThreshold) {
        this.adminApprovalThreshold = adminApprovalThreshold;
    }

    public int getAdminApprovalTimeoutHours() {
        return adminApprovalTimeoutHours;
    }

    public void setAdminApprovalTimeoutHours(int adminApprovalTimeoutHours) {
        this.adminApprovalTimeoutHours = adminApprovalTimeoutHours;
    }

    public String getAdminApprovalExpiryCron() {
        return adminApprovalExpiryCron;
    }

    public void setAdminApprovalExpiryCron(String adminApprovalExpiryCron) {
        this.adminApprovalExpiryCron = adminApprovalExpiryCron;
    }
}
