package com.softropic.payam.email.contract;

public enum EmailTemplate {
    NONE(""),
    ACTIVATION("email.activation.title"),
    CREATION_DUP("email.creation_dup.title"),
    PASSWORD_RESET("email.pw_reset.title"),
    SEND_OTP("email.otp.title"),
    EMAIL_CHANGE("email.change.title"),
    POST_PURCHASE("email.post_purchase.title"),
    PROFILE_CHANGE("email.profile_change.title"),
    PLATFORM_CONFIG_CHANGED("email.platform_config_changed.title"),
    TENANT_API_KEY_GENERATED("email.tenant.api_key_generated.title"),
    TENANT_API_KEY_ROTATED("email.tenant.api_key_rotated.title"),
    TENANT_API_KEY_REVOKED("email.tenant.api_key_revoked.title"),
    TENANT_API_KEY_REACTIVATED("email.tenant.api_key_reactivated.title"),
    TENANT_WEBHOOK_SECRET_REGENERATED("email.tenant.webhook_secret_regenerated.title"),
    TENANT_STATUS_CHANGED("email.tenant.status_changed.title");

    private final String subjectKey;
    EmailTemplate(final String subjectKey) {
        this.subjectKey = subjectKey;
    }

    public String subjectKey() {
        return subjectKey;
    }
}
