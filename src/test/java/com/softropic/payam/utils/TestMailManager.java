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

    @EventListener
    @Override
    public void sendEmailFromTemplate(final Envelope envelope) {
        sendEmailSync(envelope);
    }

    public Envelope getEnvelope(String referenceId) {
        return sentMails.get(referenceId);
    }
}
