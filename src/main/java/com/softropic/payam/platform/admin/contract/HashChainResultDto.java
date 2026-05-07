package com.softropic.payam.platform.admin.contract;

/**
 * Result of a single-transaction hash chain verification.
 *
 * @param transactionId the transaction whose chain was verified
 * @param valid         {@code true} if every link in the chain is intact; {@code false} if
 *                      any event hash or previousHash link has been tampered with
 */
public record HashChainResultDto(String transactionId, boolean valid) {
}
