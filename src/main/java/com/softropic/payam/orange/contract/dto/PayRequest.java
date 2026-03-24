package com.softropic.payam.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PayRequest {
    @JsonProperty("merchant_key") private String merchantKey;
    @JsonProperty("currency") private String currency;
    @JsonProperty("order_id") private String orderId;
    @JsonProperty("amount") private String amount;
    @JsonProperty("return_url") private String returnUrl;
    @JsonProperty("cancel_url") private String cancelUrl;
    @JsonProperty("notif_url") private String notifUrl;
    @JsonProperty("lang") private String lang;       // "fr" default
    @JsonProperty("reference") private String reference;

    public static PayRequest of(String merchantKey, String currency, String orderId,
                                String amount, String reference) {
        PayRequest req = new PayRequest();
        req.merchantKey = merchantKey;
        req.currency = currency;
        req.orderId = orderId;
        req.amount = amount;
        req.reference = reference;
        req.lang = "fr";
        return req;
    }

    public String getMerchantKey() { return merchantKey; }
    public String getCurrency() { return currency; }
    public String getOrderId() { return orderId; }
    public String getAmount() { return amount; }
    public String getReturnUrl() { return returnUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public String getNotifUrl() { return notifUrl; }
    public String getLang() { return lang; }
    public String getReference() { return reference; }

    public PayRequest withReturnUrl(String returnUrl) { this.returnUrl = returnUrl; return this; }
    public PayRequest withCancelUrl(String cancelUrl) { this.cancelUrl = cancelUrl; return this; }
    public PayRequest withNotifUrl(String notifUrl) { this.notifUrl = notifUrl; return this; }
    public PayRequest withLang(String lang) { this.lang = lang; return this; }
}
