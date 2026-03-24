package com.softropic.payam.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CashoutRequest {
    @JsonProperty("merchant_key") private String merchantKey;
    @JsonProperty("amount") private String amount;
    @JsonProperty("currency") private String currency;
    @JsonProperty("reference") private String reference;
    @JsonProperty("msisdn") private String msisdn;   // national number, no country code

    public String getMerchantKey() { return merchantKey; }
    public void setMerchantKey(String merchantKey) { this.merchantKey = merchantKey; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getMsisdn() { return msisdn; }
    public void setMsisdn(String msisdn) { this.msisdn = msisdn; }
}
