package com.softropic.payam.orange.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PayRequest {

    @JsonProperty("payToken")          private String payToken;
    @JsonProperty("subscriberMsisdn")  private String subscriberMsisdn;
    @JsonProperty("channelUserMsisdn") private String channelUserMsisdn;
    @JsonProperty("amount")            private String amount;
    @JsonProperty("orderId")           private String orderId;
    @JsonProperty("description")       private String description;
    @JsonProperty("notifUrl")          private String notifUrl;
    @JsonProperty("pin")               private String pin;

    /** Factory method — preferred over direct constructor for readability. */
    public static PayRequest of(String payToken, String subscriberMsisdn,
                                String channelUserMsisdn, String amount,
                                String orderId, String description, String notifUrl,
                                String pin) {
        PayRequest req = new PayRequest();
        req.payToken          = payToken;
        req.subscriberMsisdn  = subscriberMsisdn;
        req.channelUserMsisdn = channelUserMsisdn;
        req.amount            = amount;
        req.orderId           = orderId;
        req.description       = description;
        req.notifUrl          = notifUrl;
        req.pin               = pin;
        return req;
    }

    public String getPayToken()          { return payToken; }
    public String getSubscriberMsisdn()  { return subscriberMsisdn; }
    public String getChannelUserMsisdn() { return channelUserMsisdn; }
    public String getAmount()            { return amount; }
    public String getOrderId()           { return orderId; }
    public String getDescription()       { return description; }
    public String getNotifUrl()          { return notifUrl; }
    public String getPin()               { return pin; }
}
