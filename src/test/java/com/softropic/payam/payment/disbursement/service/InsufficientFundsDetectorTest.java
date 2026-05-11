package com.softropic.payam.disbursement.service;

import com.softropic.payam.common.payment.ProviderResult;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InsufficientFundsDetectorTest {

    private final InsufficientFundsDetector sut = new InsufficientFundsDetector();

    @Test
    void detectsMtnNotEnoughFundsCode() {
        ProviderResult r = new ProviderResult(null, null, false,
                "NOT_ENOUGH_FUNDS", "Account has insufficient balance");
        assertThat(sut.isInsufficientFunds(r)).isTrue();
    }

    @Test
    void detectsOrangeInsufficientBalanceCode() {
        ProviderResult r = new ProviderResult(null, null, false,
                "INSUFFICIENT_BALANCE", "x");
        assertThat(sut.isInsufficientFunds(r)).isTrue();
    }

    @Test
    void rejectsTimeoutErrorCode() {
        ProviderResult r = new ProviderResult(null, null, false,
                "TIMEOUT", "Request timed out");
        assertThat(sut.isInsufficientFunds(r)).isFalse();
    }

    @Test
    void detectsPatternInErrorMessageCaseInsensitive() {
        ProviderResult r = new ProviderResult(null, null, false,
                null, "Provider responded: not enough funds available");
        assertThat(sut.isInsufficientFunds(r)).isTrue();
    }

    @Test
    void nullResultReturnsFalse() {
        assertThat(sut.isInsufficientFunds(null)).isFalse();
    }

    @Test
    void allFieldsNullReturnsFalse() {
        ProviderResult r = new ProviderResult(null, null, false, null, null);
        assertThat(sut.isInsufficientFunds(r)).isFalse();
    }
}
