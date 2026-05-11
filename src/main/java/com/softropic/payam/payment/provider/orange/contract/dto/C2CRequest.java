package com.softropic.payam.payment.provider.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class C2CRequest {
    @JsonProperty("merchant_key") private String merchantKey;
    @JsonProperty("amount") private String amount;
    @JsonProperty("currency") private String currency;
    @JsonProperty("reference") private String reference;
    @JsonProperty("msisdn_to") private String msisdnTo;    // national number
    @JsonProperty("msisdn_from") private String msisdnFrom; // national number

    public String getMerchantKey() { return merchantKey; }
    public void setMerchantKey(String merchantKey) { this.merchantKey = merchantKey; }

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getMsisdnTo() { return msisdnTo; }
    public void setMsisdnTo(String msisdnTo) { this.msisdnTo = msisdnTo; }

    public String getMsisdnFrom() { return msisdnFrom; }
    public void setMsisdnFrom(String msisdnFrom) { this.msisdnFrom = msisdnFrom; }
}
