package com.softropic.payam.platform.admin.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Pattern;

/**
 * Request/response record for the platform configuration API.
 *
 * <p>Used for:
 * <ul>
 *   <li>{@code GET /v1/admin/platform-config} (response list element) — server populates
 *       {@code provider}, {@code platformMsisdn}, {@code pinConfigured};
 *       {@code pin} is always {@code null} on the read path and is OMITTED from the JSON
 *       response thanks to {@link JsonInclude.Include#NON_NULL} (PIN-04 — PIN value never
 *       appears in this response).</li>
 *   <li>{@code GET /v1/admin/platform-config/{provider}} (response, single element) — same
 *       shape as the list element.</li>
 *   <li>{@code PUT /v1/admin/platform-config/{provider}} (request body) — client sets
 *       {@code platformMsisdn} and optionally {@code pin}; {@code pinConfigured} is
 *       computed by the server and ignored on input.</li>
 * </ul>
 *
 * <p>{@code pin} validation (PIN-03):
 * <ul>
 *   <li>Bean Validation 3.x skips {@code @Pattern} when the value is {@code null} — this
 *       allows MSISDN-only PUT requests to omit the pin field.</li>
 *   <li>Empty string ({@code ""}) is explicitly allowed by the regex alternation
 *       ({@code ^$|...}) so that the frontend can send an empty pin to mean
 *       "do not change the existing PIN" (PIN-08).</li>
 *   <li>Non-empty values must match {@code [a-zA-Z0-9]{4,8}} — alphanumeric, 4–8 chars.
 *       Anything else triggers HTTP 400 via {@code ApiAdvice}'s
 *       {@code MethodArgumentNotValidException} handler.</li>
 * </ul>
 * The {@code message} attribute uses the project's {@code "errorKey|fallbackMessage"}
 * format consumed by {@code ApiAdvice.processFieldErrors()} for i18n lookup.
 *
 * @param provider        the provider key, e.g. {@code "ORANGE"} or {@code "MTN"}
 * @param platformMsisdn  the platform-owned MSISDN for this provider; empty string if not yet set
 * @param pinConfigured   server-computed: {@code true} when the persisted PIN is non-null,
 *                        {@code false} otherwise. Ignored on inbound requests.
 * @param pin             write-only on PUT; always {@code null} on GET responses; omitted
 *                        from JSON output when {@code null}. Validated as alphanumeric 4–8
 *                        chars (or empty to keep existing PIN).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformConfigDto(
    String provider,
    String platformMsisdn,
    boolean pinConfigured,
    @Pattern(
        regexp = "^$|^[a-zA-Z0-9]{4,8}$",
        message = "invalid.pin|PIN must be alphanumeric and 4\u20138 characters, or empty to keep existing PIN"
    )
    String pin
) {}
