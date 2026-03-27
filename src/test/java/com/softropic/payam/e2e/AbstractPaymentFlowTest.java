package com.softropic.payam.e2e;

import org.junit.jupiter.api.Test;

/**
 * Four-phase template method for standard payment flow tests.
 *
 * <p>Concrete subclasses must implement all four abstract methods.
 * The {@code runFlow()} orchestration is final — subclasses cannot
 * override the test entry point or alter the phase order.</p>
 *
 * <p>Phase contract:</p>
 * <ol>
 *   <li>{@link #setupPreconditions()} — seed tenant, stubs, fee rules</li>
 *   <li>{@link #executeFlow()} — POST /v1/payments, capture response</li>
 *   <li>{@link #simulateProviderCallback()} — dispatch webhook or poll result</li>
 *   <li>{@link #verifyFinalState()} — assert DB, Redis, response fields</li>
 * </ol>
 */
public abstract class AbstractPaymentFlowTest extends AbstractPayamE2ETest {

    @Test
    final void runFlow() {
        setupPreconditions();
        executeFlow();
        simulateProviderCallback();
        verifyFinalState();
    }

    protected abstract void setupPreconditions();

    protected abstract void executeFlow();

    protected abstract void simulateProviderCallback();

    protected abstract void verifyFinalState();
}
