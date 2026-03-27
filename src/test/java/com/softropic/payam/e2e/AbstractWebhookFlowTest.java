package com.softropic.payam.e2e;

/**
 * Webhook-specialised payment flow test.
 *
 * <p>Refines {@link AbstractPaymentFlowTest} for tests where the provider
 * calls back via inbound webhook (Orange, MTN). The two parent template
 * phases are sealed here: {@code simulateProviderCallback()} dispatches
 * the inbound webhook, and {@code verifyFinalState()} asserts both the
 * double-check trigger and the resulting transaction state.</p>
 *
 * <p>Concrete subclasses implement:</p>
 * <ul>
 *   <li>{@link #setupPreconditions()}</li>
 *   <li>{@link #executeFlow()}</li>
 *   <li>{@link #dispatchInboundWebhook()}</li>
 *   <li>{@link #verifyDoubleCheckTriggered()}</li>
 *   <li>{@link #verifyTransactionState()}</li>
 * </ul>
 */
public abstract class AbstractWebhookFlowTest extends AbstractPaymentFlowTest {

    @Override
    protected final void simulateProviderCallback() {
        dispatchInboundWebhook();
    }

    @Override
    protected final void verifyFinalState() {
        verifyDoubleCheckTriggered();
        verifyTransactionState();
    }

    protected abstract void dispatchInboundWebhook();

    protected abstract void verifyDoubleCheckTriggered();

    protected abstract void verifyTransactionState();
}
