# Fraud Engine Guide

This document explains the concepts, architecture, and configuration of the Payam fraud engine. It is designed for developers and operators who need to understand how fraud detection works in the system or how to tune its rules.

## Overview

The fraud engine is a pre-dispatch interceptor that evaluates every payment initiation attempt before it is sent to the mobile money provider (MTN, Orange, etc.). Its primary goal is to prevent fraudulent attempts from reaching the provider, thereby avoiding double-charges or unnecessary load on provider APIs.

The engine uses a combination of **velocity checks** and **risk scoring** to determine whether a payment should be allowed or blocked.

## Key Concepts

### 1. Fraud Signals
A fraud signal is a specific dimension or "pattern" being monitored. The current signals are:
- **IP_VELOCITY**: Too many payments from the same client IP address.
- **MSISDN_VELOCITY**: Too many payments to the same mobile number.
- **APP_VELOCITY**: Too many payments from the same tenant application.
- **MSISDN_HOUSEHOLD**: A SIM-sharing pattern, detected when multiple payments are made to mobile numbers with the same prefix (first 9 digits).

### 2. Velocity Threshold
The maximum number of payment attempts allowed for a specific signal within its configured time window.
- *Example*: If the `MSISDN_VELOCITY` threshold is 5, a user can attempt at most 5 payments to the same mobile number within the window.

### 3. Window (Window Seconds)
The duration (in seconds) over which the threshold is enforced. The velocity check uses a "token-bucket" algorithm where the "bucket" of allowed attempts (tokens) refills over this period.
- *Example*: If `IP_VELOCITY` has a threshold of 10 and a window of 60 seconds, the IP can make 10 attempts per minute.

### 4. Weight
Every fraud signal has an assigned weight (0-100). When a signal's velocity threshold is exceeded, its weight is added to the total **Risk Score** for that payment.
- *Example*: `IP_VELOCITY` (weight 40) + `MSISDN_VELOCITY` (weight 35) = Risk Score of 75.

### 5. Risk Score
A weighted sum of all violated signals, clamped between 0 and 100. The risk score represents the engine's confidence that a request is fraudulent.
- A score of **0** means no velocity limits were hit.
- A score of **100** means extreme fraud risk.

### 6. Block Threshold
A global threshold (default 70) used to decide if a payment should be blocked based on its total risk score. If `Risk Score >= Block Threshold`, the payment is blocked.

### 7. Direct Velocity Block
To ensure strict security, **any single velocity signal exceeded triggers an immediate block**, regardless of whether the total risk score reaches the block threshold. This ensures that even if a signal's weight is low, exceeding its limit is always treated as a violation.

## How it Works

1.  **Request Arrival**: A payment request reaches the `PaymentOrchestrator`.
2.  **Signal Extraction**: The engine extracts the client IP, MSISDN, tenant ID, and device fingerprint.
3.  **Velocity Checks**: The engine checks each signal against its Redis-backed "bucket." One token is consumed per attempt.
4.  **Scoring**: If a bucket is empty (threshold exceeded), the signal is marked as violated, and its weight is added to the risk score.
5.  **Decision**:
    *   If **any** velocity limit was exceeded → **BLOCK**.
    *   If the **total risk score >= block threshold** → **BLOCK**.
    *   Otherwise → **ALLOW**.
6.  **Action**: If blocked, the orchestrator returns an `HTTP 422 Unprocessable Entity` with the error code `FRAUD_BLOCKED`. No provider call is made.

## Configuration (Hot-Reloadable)

Fraud rules are stored in the `main.fraud_rule` database table. The application caches these rules and refreshes them every 60 seconds (configurable), so **no application restart is required** when changing rules.

### Changing a Rule
To change the threshold or weight of a signal, update its row in the `main.fraud_rule` table:

```sql
-- Lower MSISDN_VELOCITY threshold to 3 and increase its weight to 50
UPDATE main.fraud_rule 
SET threshold = 3, weight = 50 
WHERE signal_name = 'MSISDN_VELOCITY';
```

### Changing the Block Threshold
The block threshold is also stored in the `main.fraud_rule` table with the signal name `BLOCK_THRESHOLD`:

```sql
-- Change the global block threshold to 80
UPDATE main.fraud_rule 
SET threshold = 80 
WHERE signal_name = 'BLOCK_THRESHOLD';
```

### Disabling a Rule
To stop monitoring a specific signal:

```sql
UPDATE main.fraud_rule 
SET enabled = false 
WHERE signal_name = 'IP_VELOCITY';
```

## Technical Details

- **Redis Storage**: Velocity buckets are stored in Redis using the `Bucket4j` library. This ensures that limits are enforced across multiple application nodes and survive application restarts.
- **Performance**: Fraud scoring is non-transactional and happens before the database transaction for the payment is committed, ensuring high throughput.
- **Persistence**: Every transaction's final `risk_score` and `device_fingerprint` are stored in the `main.transaction` table for auditing and future analysis.

## Summary Table of Default Rules

| Signal Name | Weight | Threshold | Window (sec) | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| **IP_VELOCITY** | 40 | 10 | 60 | Prevent IP-based bot attacks. |
| **MSISDN_VELOCITY** | 35 | 5 | 60 | Prevent rapid attempts to the same phone. |
| **APP_VELOCITY** | 25 | 20 | 60 | Protect tenants from credential stuffing. |
| **MSISDN_HOUSEHOLD**| 15 | 8 | 3600 | Detect SIM-sharing patterns (longer window). |
| **BLOCK_THRESHOLD** | N/A | 70 | N/A | Risk score limit for blocking. |
