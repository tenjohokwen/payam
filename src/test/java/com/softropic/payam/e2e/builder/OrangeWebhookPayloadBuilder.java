package com.softropic.payam.e2e.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fluent builder for {@link OrangeWebhookPayload} test data (BUILD-05).
 *
 * <p>Critical: the {@code createtime} field must be in WAT (Africa/Douala, UTC+1) local format
 * {@code yyyy-MM-dd HH:mm:ss} with NO timezone offset appended. This matches what
 * {@code OrangeTimeUtil.parseOrangeTimestamp} expects — it applies the +01:00 offset implicitly.
 *
 * <p>Usage:
 * <pre>
 *     OrangeWebhookPayload payload = new OrangeWebhookPayloadBuilder()
 *         .forPayToken(payToken)
 *         .asSuccessful()
 *         .build();
 * </pre>
 */
public class OrangeWebhookPayloadBuilder {

    private static final ZoneId WAT = ZoneId.of("Africa/Douala");
    private static final DateTimeFormatter WAT_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String payToken;
    private String notifToken = UUID.randomUUID().toString();
    private String status = "SUCCESS";
    private String txnid = UUID.randomUUID().toString();
    private String msisdn = "237690000002";
    private String amount = "1000";
    private String createtime = formatWat(Instant.now());

    private static String formatWat(Instant instant) {
        return WAT_FORMAT.format(instant.atZone(WAT));
    }

    /**
     * Sets the payToken (the Orange correlation key used to look up the Payam transaction).
     */
    public OrangeWebhookPayloadBuilder forPayToken(String payToken) {
        this.payToken = payToken;
        return this;
    }

    public OrangeWebhookPayloadBuilder withNotifToken(String notifToken) {
        this.notifToken = notifToken;
        return this;
    }

    public OrangeWebhookPayloadBuilder withStatus(String status) {
        this.status = status;
        return this;
    }

    public OrangeWebhookPayloadBuilder withTxnid(String txnid) {
        this.txnid = txnid;
        return this;
    }

    public OrangeWebhookPayloadBuilder withMsisdn(String msisdn) {
        this.msisdn = msisdn;
        return this;
    }

    public OrangeWebhookPayloadBuilder withAmount(String amount) {
        this.amount = amount;
        return this;
    }

    /**
     * Sets createtime from a UTC Instant, converting to WAT local format
     * (yyyy-MM-dd HH:mm:ss, no offset).
     */
    public OrangeWebhookPayloadBuilder withCreatetime(Instant instant) {
        this.createtime = formatWat(instant);
        return this;
    }

    /**
     * Sets createtime from a WAT ZonedDateTime, formatting directly.
     */
    public OrangeWebhookPayloadBuilder withCreatetime(ZonedDateTime watDateTime) {
        this.createtime = WAT_FORMAT.format(watDateTime.withZoneSameInstant(WAT));
        return this;
    }

    public OrangeWebhookPayloadBuilder asSuccessful() {
        this.status = "SUCCESS";
        return this;
    }

    public OrangeWebhookPayloadBuilder asFailed() {
        this.status = "FAILED";
        return this;
    }

    /**
     * Builds and returns an {@link OrangeWebhookPayload} with all configured fields.
     *
     * <p>Uses Jackson ObjectMapper.convertValue so the @JsonProperty mappings
     * (e.g. "notif_token") are honoured exactly as in production deserialization.
     */
    public OrangeWebhookPayload build() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("payToken", payToken);
        props.put("notif_token", notifToken);
        props.put("status", status);
        props.put("txnid", txnid);
        props.put("msisdn", msisdn);
        props.put("amount", amount);
        props.put("createtime", createtime);
        return MAPPER.convertValue(props, OrangeWebhookPayload.class);
    }
}
