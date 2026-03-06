package com.softropic.payam.email.persistence.repository;



import com.softropic.payam.email.persistence.entity.EnvelopeEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;


public interface EnvelopeEntityRepository extends JpaRepository<EnvelopeEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE) // keep the @Lock annotation as a matter of best practice and framework/code clarity, even though the FOR UPDATE SKIP LOCKED clause is doing the heavy lifting in the native SQL
    @Query(value = "SELECT * FROM main.envelope_entity e WHERE e.retry = 'true' AND e.deadline > now()  AND e.status = 'FAILED' ORDER BY e.deadline LIMIT 10 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<EnvelopeEntity> fetchFailedEmails();

    @Lock(LockModeType.PESSIMISTIC_WRITE) // keep the @Lock annotation as a matter of best practice and framework/code clarity, even though the FOR UPDATE SKIP LOCKED clause is doing the heavy lifting in the native SQL
    EnvelopeEntity findBySendId(String sendId);
}
