package com.softropic.payam.platform.admin.contract.event;

/**
 * Spring application event published when a platform config row is updated by admin.
 *
 * <p>Published inside the {@code @Transactional} update() method so that
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners fire correctly
 * after the DB transaction commits.
 *
 * <p>Fire rules (PIN-10):
 * <ul>
 *   <li>Fires when MSISDN changes OR an existing PIN is replaced with a new PIN.</li>
 *   <li>Does NOT fire when both msisdnChanged and pinChanged are false.</li>
 *   <li>Does NOT fire on first-time PIN creation (oldPin was null).</li>
 *   <li>Does NOT fire from the new-row (orElseGet) creation path.</li>
 * </ul>
 *
 * @param provider        the provider whose config was updated, e.g. "ORANGE" or "MTN"
 * @param oldMsisdn       the previous MSISDN value (empty string if it was not yet set)
 * @param newMsisdn       the new MSISDN value
 * @param msisdnChanged   true when newMsisdn != oldMsisdn
 * @param pinChanged      true when a non-blank PIN replaced an existing (non-null) PIN;
 *                        false on first-time PIN creation per PIN-10
 * @param changedBy       the admin username resolved via SecurityUtil on the request
 *                        thread; "unknown" when no authentication is present
 */
public record PlatformConfigChangedEvent(
    String provider,
    String oldMsisdn,
    String newMsisdn,
    boolean msisdnChanged,
    boolean pinChanged,
    String changedBy
) {}
