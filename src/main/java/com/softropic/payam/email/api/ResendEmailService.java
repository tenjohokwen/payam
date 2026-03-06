package com.softropic.payam.email.api;


import com.softropic.payam.email.persistence.entity.EnvelopeEntity;
import com.softropic.payam.email.persistence.repository.EnvelopeEntityRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ResendEmailService {

    private final EnvelopeEntityRepository  envelopeEntityRepository;
    private final ApplicationEventPublisher publisher;

    public void resendEmail(String sendId) {
        EnvelopeEntity envelopeEntity = envelopeEntityRepository.findBySendId(sendId);
        final Envelope envelop = EnvelopeMapper.toEnvelop(envelopeEntity);
        publisher.publishEvent(envelop);
    }
}
