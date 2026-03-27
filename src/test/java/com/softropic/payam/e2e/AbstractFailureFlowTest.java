package com.softropic.payam.e2e;

import org.junit.jupiter.api.Test;

/**
 * Failure-injection template for E2E tests that verify error handling.
 *
 * <p>Extends {@link AbstractPayamE2ETest} directly (not {@link AbstractPaymentFlowTest})
 * because failure flows have a different phase structure: fault injection
 * precedes the flow execution rather than happening after it.</p>
 *
 * <p>Phase contract:</p>
 * <ol>
 *   <li>{@link #setupPreconditions()} — seed tenant, configure stubs for base state</li>
 *   <li>{@link #injectFault()} — configure WireMock error stubs, flip circuit breaker, etc.</li>
 *   <li>{@link #executeFlow()} — trigger the payment/webhook that should fail</li>
 *   <li>{@link #verifyFailureHandled()} — assert error response, status, DB state</li>
 * </ol>
 *
 * <p>{@code runFailureScenario()} is final — subclasses cannot alter the phase order.</p>
 */
public abstract class AbstractFailureFlowTest extends AbstractPayamE2ETest {

    @Test
    final void runFailureScenario() {
        setupPreconditions();
        injectFault();
        executeFlow();
        verifyFailureHandled();
    }

    protected abstract void setupPreconditions();

    protected abstract void injectFault();

    protected abstract void executeFlow();

    protected abstract void verifyFailureHandled();
}
