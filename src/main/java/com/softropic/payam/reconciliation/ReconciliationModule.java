package com.softropic.payam.reconciliation;

/**
 * Spring Modulith boundary marker for the Reconciliation module.
 *
 * This is a plain marker class — spring-modulith-starter-jpa is not in pom.xml;
 * @ApplicationModule is unavailable. This class serves as boundary documentation only.
 *
 * Responsibilities:
 * - Daily Quartz job compares Payam transaction ledger against provider records
 * - Detects discrepancies: MISSING_IN_PROVIDER, AMOUNT_MISMATCH, STATUS_MISMATCH, UNCONFIRMED
 * - Persists ReconciliationReport and ReconciliationDiscrepancy rows for admin review
 */
public class ReconciliationModule {
}
