package com.softropic.payam.security.audit.shared.event;


import com.softropic.payam.email.api.Recipient;

public class AccountChangeEvent {
    private final Action    action;
    private final String    oldValue;
    private final String    newValue;
    private final Recipient recipient;

    public AccountChangeEvent(Action action, String oldValue, String newValue, Recipient recipient) {
        this.action = action;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.recipient = recipient;
    }

    public Action getAction() {
        return action;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public Recipient getRecipient() {
        return recipient;
    }

    public enum Action {
        PASSWORD_CHANGED, //send email
        EMAIL_CHANGED, //send email to old address
        ADDRESS_CHANGED, //send email
        PHONE_CHANGED, //send email
        TWO_FACTOR_AUTH_ENABLED, //send email
        TWO_FACTOR_AUTH_DISABLED, //send email
        OTHERS;
    }
}
