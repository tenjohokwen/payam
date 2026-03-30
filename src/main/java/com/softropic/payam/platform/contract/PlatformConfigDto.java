package com.softropic.payam.platform.contract;

/**
 * Request/response record for the platform configuration API.
 *
 * <p>Used for GET /v1/admin/platform-config (response list element) and
 * PUT /v1/admin/platform-config/{provider} (request body and response body).
 *
 * @param provider       the provider key, e.g. "ORANGE" or "MTN"
 * @param platformMsisdn the platform-owned MSISDN for this provider; empty string if not yet set
 */
public record PlatformConfigDto(String provider, String platformMsisdn) {}
