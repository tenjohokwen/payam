package com.softropic.payam.email.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;


@Configuration
@ConfigurationProperties(prefix = "email")
public class MailSenderProvider {
    private final AtomicInteger counter = new AtomicInteger(0);
    private final List<JavaMailSenderImpl> providers = new ArrayList<>();
    private final List<ProviderConfig> providerConfigs = new ArrayList<>();

    @PostConstruct
    public void init() {
       providerConfigs.forEach(providerConfig -> providers.add(toMailSender(providerConfig)));
    }

    private JavaMailSenderImpl toMailSender(final ProviderConfig providerConfig) {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setDefaultEncoding(String.valueOf(StandardCharsets.UTF_8));
        javaMailSender.setHost(providerConfig.getHost());
        javaMailSender.setPort(Integer.parseInt(providerConfig.getPort()));
        javaMailSender.setPassword(providerConfig.getPassword());
        javaMailSender.setUsername(providerConfig.getUsername());
        javaMailSender.setProtocol("smtp");
        Properties props = javaMailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.debug", "true");
        return javaMailSender;
    }

    public JavaMailSenderImpl nextSender() {
        final var currentCounterValue = counter.getAndIncrement();
        final var nextProviderPos = currentCounterValue % providers.size();
        return providers.get(nextProviderPos);
    }

    /*
      NB a getter/setter method is needed for injection. Spring autowiring feature.
     */
    public List<ProviderConfig> getProviderConfigs() {
        return this.providerConfigs;
    }

    void resetProviderRoundRobin() {
        counter.set(0);
    }
}
