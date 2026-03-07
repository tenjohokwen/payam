package com.softropic.payam.utils;



import com.softropic.payam.email.api.Envelope;
import com.softropic.payam.email.api.MailManager;

import org.springframework.context.event.EventListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class TestMailManager extends MailManager {
    private final Map<String, Envelope> sentMails = new ConcurrentHashMap<>();

    public TestMailManager() {
        super(null, null, null, null);
    }

    @Override
    public void sendEmailSync(final Envelope envelope) {
        sentMails.put(envelope.sendId(), envelope);
    }

    /**
     * Captures envelope events synchronously regardless of transaction state.
     * Uses a distinct method name so Spring's TransactionalEventListenerFactory
     * cannot claim it via the parent's @TransactionalEventListener annotation.
     */
    @EventListener
    public void onEnvelope(final Envelope envelope) {
        sendEmailSync(envelope);
    }

    @Override
    public void sendEmailFromTemplate(final Envelope envelope) {
        sendEmailSync(envelope);
    }

    public Envelope getEnvelope(String referenceId) {
        return sentMails.get(referenceId);
    }

    public void clear() {
        sentMails.clear();
    }
}
