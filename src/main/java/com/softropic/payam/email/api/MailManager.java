package com.softropic.payam.email.api;


import com.softropic.payam.email.persistence.entity.EmailDeliveryStatus;
import com.softropic.payam.email.persistence.entity.EnvelopeEntity;
import com.softropic.payam.email.persistence.repository.EnvelopeEntityRepository;
import com.softropic.payam.email.service.MailService;

import jakarta.mail.MessagingException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class MailManager {

    private static final Logger      logger = LoggerFactory.getLogger(MailManager.class);

    private final MailService mailService;

    private final EnvelopeEntityRepository envelopeEntityRepository;

    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private final RetryTemplate retryTemplate;

    private static final List<Class<? extends Exception>> NON_REPAIRABLE_ERRORS = List.of(MailParseException.class,
                                                                                          MailPreparationException.class);

    public MailManager(final MailService mailService,
                       final EnvelopeEntityRepository envelopeEntityRepository,
                       final CircuitBreakerFactory<?, ?> circuitBreakerFactory,
                       final RetryTemplate retryTemplate) {
        this.mailService = mailService;
        this.envelopeEntityRepository = envelopeEntityRepository;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.retryTemplate = retryTemplate;
    }

    @Async("sendMailPool")
    @EventListener
    @Transactional
    public void sendEmailFromTemplate(final Envelope envelope) {
        sendEmailSync(envelope);
    }

    public void sendEmailSync(final Envelope envelope) {
        logger.info("sendEmailFrom template called:  Envelope {}", envelope);
        final List<Recipient> recipients = envelope.recipients();
        if(recipients == null || recipients.isEmpty()) {
            //TODO add validation to model so that the following line of code is never executed
            throw new IllegalStateException("Recipient is missing. Cannot process email send request");
        }
        
        final CircuitBreaker circuitBreaker = circuitBreakerFactory.create("emailService");

        EnvelopeEntity envelopeEntity;
        try {
            circuitBreaker.run(() -> {
                for (Recipient recipient : recipients) {
                    final Map<String, Object> data = new HashMap<>(envelope.data());
                    data.put("sendId", envelope.sendId());
                    
                    retryTemplate.execute(context -> {
                        try {
                            mailService.sendEmailFromTemplate(recipient, envelope.emailTemplate(), data);
                        } catch (MessagingException e) {
                            if (isRetryable(e)) {
                                throw new RuntimeException("Retryable email error", e);
                            }
                            throw new RuntimeException("Non-retryable email error", e);
                        } catch (Exception e) {
                            if (isRetryable(e)) {
                                throw new RuntimeException("Unexpected retryable email error", e);
                            }
                            throw new RuntimeException("Unexpected non-retryable email error", e);
                        }
                        return null;
                    });
                }
                return null;
            }, throwable -> {
                if (throwable instanceof RuntimeException && throwable.getCause() != null) {
                    throw (RuntimeException) throwable;
                }
                throw new RuntimeException("Email sending failed via Circuit Breaker", throwable);
            });
            envelopeEntity = toEnvelopeEntity(envelope, null);
        } catch (Exception exception) {
            //If failure occurs it is either an authentication issue or network issue
            //This means you would not have some sent and others fail. It would be an all or none
            envelopeEntity = toEnvelopeEntity(envelope, exception);
            logger.error("Could not send email after retries and circuit breaker protection. {}", envelopeEntity, exception);
        }
        final EnvelopeEntity entityBySendId = envelopeEntityRepository.findBySendId(envelopeEntity.getSendId());
        if(entityBySendId != null) {
            entityBySendId.setAttempts(entityBySendId.getAttempts() + 1);
            entityBySendId.setStatus(envelopeEntity.getStatus());
            entityBySendId.setError(envelopeEntity.getError());
            entityBySendId.setRetry(envelopeEntity.isRetry());
        } else {
            envelopeEntityRepository.save(envelopeEntity);
        }
    }

    private EnvelopeEntity toEnvelopeEntity(final Envelope envelope, final Exception exception) {
        final EnvelopeEntity envelopeEntity = EnvelopeMapper.toEntity(envelope);
        long attempts = envelopeEntity.getAttempts();
        envelopeEntity.setAttempts(++attempts);
        if(exception != null) {
            final String stacktrace = ExceptionUtils.getStackTrace(exception);
            envelopeEntity.setError(stacktrace);
            envelopeEntity.setStatus(EmailDeliveryStatus.FAILED);
            envelopeEntity.setRetry(isRetryable(exception));
        } else {
            envelopeEntity.setRetry(false);
            envelopeEntity.setStatus(EmailDeliveryStatus.SENT);
        }
        return envelopeEntity;
    }

    private boolean isRetryable(final Exception unknownException) {
        return NON_REPAIRABLE_ERRORS.stream().noneMatch(exceptionClass -> exceptionClass.isInstance(unknownException));
    }
}
