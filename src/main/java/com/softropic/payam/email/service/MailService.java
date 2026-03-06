package com.softropic.payam.email.service;


import com.google.common.base.CaseFormat;

import com.softropic.payam.email.api.EmailTemplate;
import com.softropic.payam.email.api.Recipient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Service for sending e-mails.
 * <p>
 * We use the @Async annotation to send e-mails asynchronously.
 * </p>
 */
@Service
public class MailService {

    private final Logger log = LoggerFactory.getLogger(MailService.class);

    private static final String RECIPIENT = "recipient";


    private final MailSenderProvider mailSenderProvider;

    private final MessageSource messageSource;

    private final SpringTemplateEngine templateEngine;

    public MailService(final MailSenderProvider mailSenderProvider,
                       final SpringTemplateEngine templateEngine,
                       final MessageSource messageSource) {
        this.mailSenderProvider = mailSenderProvider;
        this.templateEngine = templateEngine;
        this.messageSource = messageSource;
    }

    public void sendEmail(String to, String subject, String content, boolean isMultipart, boolean isHtml) throws MessagingException {
        log.debug("Send e-mail[multipart '{}' and html '{}'] to '{}' with subject '{}' and content={}",
            isMultipart, isHtml, to, subject, content);
        JavaMailSenderImpl javaMailSender = mailSenderProvider.nextSender();
        // Prepare message using a Spring helper
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper message = new MimeMessageHelper(mimeMessage,
                                                          isMultipart,
                                                          String.valueOf(StandardCharsets.UTF_8));
            message.setTo(to);
            //message.setFrom("me@localhost"); //mail providers like gmx and mail.de do not accept this. Mail should be the registered email account
            message.setFrom(javaMailSender.getUsername());
            //message.setReplyTo("tenjoh_okwen@yahoo.com"); //Use this so that the sender is not the one to whom the reply should be sent
            message.setSubject(subject);
            message.setText(content, isHtml);

            //TODO handle send failure. Could fail because quota exceeded, network issue
            javaMailSender.send(mimeMessage);
            log.debug("Sent e-mail to User '{}'", to);
    }

    public void sendEmailFromTemplate(final Recipient recipient,
                                      final EmailTemplate emailTemplate,
                                      final Map<String, Object> values) throws MessagingException {
        log.debug("Sending activation e-mail to '{}'", recipient.getEmail());
        final Locale locale = Locale.forLanguageTag( recipient.getLangKey());
        final Context context = new Context(locale);
        context.setVariable(RECIPIENT, recipient);
        context.setVariable("map", values);
        final String content = templateEngine.process(CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, emailTemplate.name()), context);
        final String subject = messageSource.getMessage(emailTemplate.subjectKey(), null, locale);
        sendEmail(recipient.getEmail(), subject, content, false, true);
    }

}
