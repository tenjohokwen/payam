package com.softropic.payam.mtn.contract.dto;

public class RequestToPayRequest {

    private String amount;       // "1000.00"
    private String currency;     // "XAF"
    private String externalId;   // our transactionId
    private Party payer;         // {partyIdType: "MSISDN", partyId: "237XXXXXXXXX"}
    private String payerMessage;
    private String payeeNote;

    public record Party(String partyIdType, String partyId) {}

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public Party getPayer() { return payer; }
    public void setPayer(Party payer) { this.payer = payer; }

    public String getPayerMessage() { return payerMessage; }
    public void setPayerMessage(String payerMessage) { this.payerMessage = payerMessage; }

    public String getPayeeNote() { return payeeNote; }
    public void setPayeeNote(String payeeNote) { this.payeeNote = payeeNote; }
}
