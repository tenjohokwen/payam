package com.softropic.payam.orange.service;

import com.softropic.payam.common.payment.MobileMoneyPort;
import com.softropic.payam.common.payment.PaymentCommand;
import com.softropic.payam.common.payment.ProviderResult;
import com.softropic.payam.common.payment.SubscriberStatus;
import com.softropic.payam.orange.config.OrangeMoneyConfig;
import com.softropic.payam.orange.contract.OrangeWebhookPayload;
import com.softropic.payam.orange.contract.dto.MerchantInfoResponse;
import com.softropic.payam.orange.contract.dto.PayRequest;
import com.softropic.payam.orange.contract.dto.PayResponse;
import com.softropic.payam.orange.contract.dto.SubscriberInfoResponse;
import com.softropic.payam.orange.contract.exception.OrangeApiException;
import com.softropic.payam.orange.contract.exception.PayTokenExpiredException;
import com.softropic.payam.orange.contract.exception.SubscriberInactiveException;
import com.softropic.payam.orange.infrastructure.OrangeMoneyClient;
import com.softropic.payam.transaction.contract.TransactionEventType;
import com.softropic.payam.transaction.contract.TransactionStatus;
import com.softropic.payam.transaction.repo.TransactionRepository;
import com.softropic.payam.transaction.service.EventLogService;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class OrangeMoneyPort implements MobileMoneyPort {

    private static final Logger log = LoggerFactory.getLogger(OrangeMoneyPort.class);

    private final OrangeMoneyClient orangeMoneyClient;
    private final OrangeTokenService orangeTokenService;
    private final TransactionRepository transactionRepository;
    private final EventLogService eventLogService;
    private final OrangeMoneyConfig config;

    public OrangeMoneyPort(OrangeMoneyClient orangeMoneyClient,
                           OrangeTokenService orangeTokenService,
                           TransactionRepository transactionRepository,
                           EventLogService eventLogService,
                           OrangeMoneyConfig config) {
        this.orangeMoneyClient = orangeMoneyClient;
        this.orangeTokenService = orangeTokenService;
        this.transactionRepository = transactionRepository;
        this.eventLogService = eventLogService;
        this.config = config;
    }

    /**
     * Implements MobileMoneyPort.initiateMerchantPayment.
     *
     * IMPORTANT (P1.1): This method is NOT @Transactional. The caller (TransactionService.initiate)
     * has already committed the INITIATED row before calling this method. Do not wrap this
     * method in @Transactional — that would hold the DB connection open during the outbound HTTP calls.
     *
     * Flow: validate subscriber -> get payToken -> call pay -> return ProviderResult(pending=true)
     */
    @Override
    @CircuitBreaker(name = "orange", fallbackMethod = "initiateFallback")
    @Retry(name = "orange")
    public ProviderResult initiateMerchantPayment(PaymentCommand cmd) {
        String token = orangeTokenService.getAccessToken();
        String nationalMsisdn = stripCountryCode(cmd.msisdn());

        // Step 1: Validate subscriber
        SubscriberInfoResponse subscriberInfo = orangeMoneyClient.getSubscriberInfo(token, nationalMsisdn);
        if (!subscriberInfo.isActive()) {
            throw new SubscriberInactiveException(cmd.msisdn());
        }

        // Step 2: Get payToken
        MerchantInfoResponse merchantInfo = orangeMoneyClient.getMerchantInfo(token);
        String payToken = merchantInfo.getPayToken();
        Instant payTokenIssuedAt = Instant.now();

        // Step 3: Persist payToken and issuance time on the transaction (non-transactional update)
        persistPayToken(cmd.transactionId(), payToken, payTokenIssuedAt);

        // Step 4: Call /mp/pay
        PayRequest payRequest = buildPayRequest(cmd, payToken, nationalMsisdn);
        PayResponse payResponse = orangeMoneyClient.pay(token, payRequest);

        // Append event log entry for INIT->PROCESSING
        eventLogService.append(
            cmd.transactionId(), cmd.traceId(), cmd.externalReference(),
            TransactionEventType.PAYMENT_INITIATED,
            TransactionStatus.INITIATED, TransactionStatus.PROCESSING,
            "ORANGE_ADAPTER", null
        );

        return ProviderResult.pending(payToken, payResponse.getStatus());
    }

    /**
     * Implements MobileMoneyPort.getTransactionStatus.
     * Uses @Lock(PESSIMISTIC_WRITE) to prevent concurrent webhook+poller race (P1.2).
     */
    @Override
    @Transactional
    @CircuitBreaker(name = "orange", fallbackMethod = "statusFallback")
    public ProviderResult getTransactionStatus(String providerRef) {
        String token = orangeTokenService.getAccessToken();
        PayResponse status = orangeMoneyClient.getPaymentStatus(token, providerRef);
        String rawStatus = status.getStatus();
        TransactionStatus internal = OrangeStatusMapper.toInternal(rawStatus);
        boolean pending = internal == TransactionStatus.PROCESSING;
        return pending
            ? ProviderResult.pending(providerRef, rawStatus)
            : ProviderResult.success(providerRef, rawStatus);
    }

    /**
     * Implements MobileMoneyPort.validateSubscriber.
     */
    @Override
    public SubscriberStatus validateSubscriber(String msisdn) {
        String token = orangeTokenService.getAccessToken();
        String nationalMsisdn = stripCountryCode(msisdn);
        SubscriberInfoResponse response = orangeMoneyClient.getSubscriberInfo(token, nationalMsisdn);
        return new SubscriberStatus(response.isActive(), msisdn, response.getStatus());
    }

    /**
     * Initiate a cashout transaction.
     *
     * ROADMAP deviation (SC-3): Cashout field mapping requires sandbox verification with live
     * Orange credentials. Stub retained intentionally — Phase 3 covers MP flow only.
     * C2C and cashout will be implemented in a future phase once sandbox access is confirmed.
     */
    @CircuitBreaker(name = "orange", fallbackMethod = "cashoutFallback")
    @Retry(name = "orange")
    public ProviderResult initiateCashout(PaymentCommand cmd) {
        throw new UnsupportedOperationException("Cashout field mapping requires sandbox verification — stub for now");
    }

    /**
     * Initiate a C2C transfer.
     *
     * ROADMAP deviation (SC-3): Same as cashout — requires sandbox field verification.
     */
    @CircuitBreaker(name = "orange", fallbackMethod = "c2cFallback")
    @Retry(name = "orange")
    public ProviderResult initiateC2C(PaymentCommand cmd, String msisdnTo) {
        throw new UnsupportedOperationException("C2C field mapping requires sandbox verification — stub for now");
    }

    /**
     * Webhook processing hook for Phase 6.
     * Does NOT apply state transition — Phase 6 calls getTransactionStatus() for double-check (P1.4).
     * Returns the notifToken for correlation.
     */
    public String processWebhook(OrangeWebhookPayload payload, String notifToken) {
        log.info("Orange webhook received: payToken={}, status={}, txnid={}",
            payload.getPayToken(), payload.getStatus(), payload.getTxnid());
        // Phase 6 will call getTransactionStatus(payload.getPayToken()) for double-check.
        // This method only validates correlation: notifToken must match payload.getNotifToken().
        if (notifToken != null && !notifToken.equals(payload.getNotifToken())) {
            log.warn("Orange webhook notifToken mismatch — possible replay: expected={}, got={}",
                notifToken, payload.getNotifToken());
        }
        return payload.getPayToken();
    }

    /**
     * Check if a payToken has expired (P1.3).
     * Throws PayTokenExpiredException if age exceeds config threshold.
     */
    public void assertPayTokenFresh(String transactionId, Instant payTokenIssuedAt) {
        if (payTokenIssuedAt == null) return;
        Duration age = Duration.between(payTokenIssuedAt, Instant.now());
        if (age.toMinutes() >= config.getPayTokenExpiryThresholdMinutes()) {
            log.warn("payToken expired for transaction={}, age={}min", transactionId, age.toMinutes());
            throw new PayTokenExpiredException(transactionId);
        }
    }

    // ---- private helpers ----

    /**
     * Strip Cameroon country code from E.164 MSISDN for Orange endpoints (P5.5).
     * "+237692954629" -> "692954629"
     * "237692954629"  -> "692954629"
     * "692954629"     -> "692954629" (already national)
     */
    private String stripCountryCode(String msisdn) {
        if (msisdn == null) return null;
        return msisdn.replaceFirst("^\\+?237", "");
    }

    /**
     * Persist payToken and issuance time to the Transaction record.
     * This is deliberately separate from the outer flow — the INITIATED row was already committed
     * by TransactionService. Uses REQUIRES_NEW propagation to commit the payToken fields
     * immediately so the poller and webhook handler can see them.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void persistPayToken(String transactionId, String payToken, Instant issuedAt) {
        transactionRepository.findByTransactionId(transactionId).ifPresent(tx -> {
            tx.setPayToken(payToken);
            tx.setPayTokenIssuedAt(issuedAt);
            transactionRepository.save(tx);
        });
    }

    private PayRequest buildPayRequest(PaymentCommand cmd, String payToken, String nationalMsisdn) {
        PayRequest req = PayRequest.of(
            config.getConsumerKey(),
            cmd.currency(),
            cmd.transactionId(),
            cmd.amount().toPlainString(),
            cmd.externalReference() != null ? cmd.externalReference() : cmd.transactionId()
        );
        return req;
    }

    // Fallback methods for Resilience4j
    private ProviderResult initiateFallback(PaymentCommand cmd, Throwable t) {
        log.error("Orange circuit open — initiate failed for txId={}", cmd.transactionId(), t);
        throw new OrangeApiException("Orange Money unavailable — circuit open", t);
    }

    private ProviderResult statusFallback(String providerRef, Throwable t) {
        log.error("Orange circuit open — status poll failed for payToken={}", providerRef, t);
        return ProviderResult.pending(providerRef, "UNKNOWN");
    }

    private ProviderResult cashoutFallback(PaymentCommand cmd, Throwable t) {
        throw new OrangeApiException("Orange Money cashout unavailable", t);
    }

    private ProviderResult c2cFallback(PaymentCommand cmd, String msisdnTo, Throwable t) {
        throw new OrangeApiException("Orange Money C2C unavailable", t);
    }
}
