package com.softropic.payam.email.api;



import com.softropic.payam.email.persistence.entity.EnvelopeEntity;
import com.softropic.payam.email.persistence.entity.RecipientEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


public class EnvelopeMapper {
    private EnvelopeMapper() {}

    static EnvelopeEntity toEntity(final Envelope envelope) {
        final EnvelopeEntity envelopeEntity = new EnvelopeEntity();
        envelopeEntity.setEmailTemplate(envelope.emailTemplate());
        envelopeEntity.setDeadline(envelope.deadline());
        envelopeEntity.setData(envelope.data());
        envelopeEntity.setRecipients(toRecipientEntities(envelope.recipients()));
        envelopeEntity.setId(UUID.randomUUID());
        envelopeEntity.setSendId(envelope.sendId());
        return envelopeEntity;
    }

    static List<RecipientEntity> toRecipientEntities(final List<Recipient> recipients) {
        final List<RecipientEntity> entities = new ArrayList<>();
        recipients.forEach(recipient -> entities.add(toRecipientEntity(recipient)));
        return entities;
    }

    static RecipientEntity toRecipientEntity(final Recipient recipient) {
        final RecipientEntity recipientEntity = new RecipientEntity();
        recipientEntity.setEmail(recipient.getEmail());
        recipientEntity.setFirstname(recipient.getFirstname());
        recipientEntity.setLastname(recipient.getLastname());
        recipientEntity.setTitle(recipient.getTitle());
        recipientEntity.setLangKey(recipient.getLangKey());
        recipientEntity.setGender(recipient.getGender());
        return recipientEntity;
    }

    static Envelope toEnvelop(final EnvelopeEntity entity) {
        Map<String, Object> data = new HashMap<>(entity.getData());
        final List<Recipient> recipients = new ArrayList<>();
        entity.getRecipients().forEach(recipient -> recipients.add(toRecipient(recipient)));
        return new Envelope(recipients,
                            entity.getEmailTemplate(),
                            entity.getDeadline(),
                            data,
                            entity.getSendId());
    }

    private static Recipient toRecipient(final RecipientEntity entity) {
        final Recipient recipient = new Recipient();
        recipient.setEmail(entity.getEmail());
        recipient.setGender(entity.getGender());
        recipient.setFirstname(entity.getFirstname());
        recipient.setLastname(entity.getLastname());
        recipient.setTitle(entity.getTitle());
        recipient.setLangKey(entity.getLangKey());
        return recipient;
    }
}
