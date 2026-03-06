package com.softropic.payam.email.api;

public enum EmailTemplate {
    NONE(""),
    ACTIVATION("email.activation.title"),
    CREATION_DUP("email.creation_dup.title"),
    PASSWORD_RESET("email.pw_reset.title"),
    SEND_OTP("email.otp.title"),
    EMAIL_CHANGE("email.change.title"),
    POST_PURCHASE("email.post_purchase.title"),
    PROFILE_CHANGE("email.profile_change.title");

    private final String subjectKey;
    EmailTemplate(final String subjectKey) {
        this.subjectKey = subjectKey;
    }

    public String subjectKey() {
        return subjectKey;
    }

}
