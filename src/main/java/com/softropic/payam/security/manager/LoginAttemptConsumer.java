package com.softropic.payam.security.manager;

public interface LoginAttemptConsumer<T> {
    void loginSucceeded(T identifier);
    void loginFailed(T identifier);
}
