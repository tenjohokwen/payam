package com.softropic.payam.platform.contract.event;

/**
 * Spring application event published when a platform MSISDN is updated by admin.
 *
 * <p>Published inside the {@code @Transactional} update() method so that
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners fire correctly
 * after the DB transaction commits.
 *
 * <p>This is a plain record (POJO event) — Spring 4.2+ supports non-ApplicationEvent events.
 * The email notification listener (plan 24-02) subscribes to this event.
 *
 * @param provider   the provider whose MSISDN was updated, e.g. "ORANGE" or "MTN"
 * @param oldMsisdn  the previous MSISDN value (empty string if it was not yet set)
 * @param newMsisdn  the new MSISDN value
 */
public record PlatformConfigChangedEvent(String provider, String oldMsisdn, String newMsisdn) {}
