package com.softropic.payam.payment.provider.mtn.contract.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 200 response from accountholder basicuserinfo. On inactive account MTN returns 404 —
 * MtnMoMoClient throws MtnAccountInactiveException instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountHolderInfoResponse {

    private String name;
    private String given_name;
    private String family_name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGiven_name() { return given_name; }
    public void setGiven_name(String given_name) { this.given_name = given_name; }

    public String getFamily_name() { return family_name; }
    public void setFamily_name(String family_name) { this.family_name = family_name; }
}
