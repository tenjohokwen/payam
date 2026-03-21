package com.softropic.payam.email.service;

import com.softropic.payam.email.contract.Envelope;
import com.softropic.payam.email.repo.EnvelopeEntity;
import com.softropic.payam.email.repo.EnvelopeEntityRepository;

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
        final Envelope envelope = EnvelopeMapper.toEnvelope(envelopeEntity);
        publisher.publishEvent(envelope);
    }
}
