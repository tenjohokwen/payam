package com.softropic.payam.mtn.contract.dto;

public class DisbursementRequest {

    private String amount;
    private String currency;
    private String externalId;
    private Party payee;         // {partyIdType: "MSISDN", partyId: "237XXXXXXXXX"}
    private String payerMessage;
    private String payeeNote;

    public record Party(String partyIdType, String partyId) {}

    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public Party getPayee() { return payee; }
    public void setPayee(Party payee) { this.payee = payee; }

    public String getPayerMessage() { return payerMessage; }
    public void setPayerMessage(String payerMessage) { this.payerMessage = payerMessage; }

    public String getPayeeNote() { return payeeNote; }
    public void setPayeeNote(String payeeNote) { this.payeeNote = payeeNote; }
}
