package com.softropic.payam.platform.admin.contract;

/**
 * Response DTO for the PIN reveal endpoint
 * {@code GET /v1/admin/platform-config/{provider}/pin}.
 *
 * <p>Carries the decrypted plaintext PIN. This DTO is dedicated to the reveal endpoint
 * and is intentionally separate from {@link PlatformConfigDto} — the standard config
 * DTO must NEVER expose the PIN value (PIN-04).
 *
 * <p>Mirrors the pattern of {@code WebhookSecretDto} in the tenant module.
 *
 * @param pin the decrypted plaintext PIN; never null when this DTO is constructed
 */
public record PinDto(String pin) {}
