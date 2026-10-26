package dev.jagadeesh.payflow.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Next unpublished events, locked with FOR UPDATE SKIP LOCKED (lock timeout -2 in Hibernate):
     * when several pods run the relay, each one takes rows the others haven't locked, so no event
     * is sent twice by two pods at the same time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    List<OutboxEvent> findByAggregateId(UUID aggregateId);

    long countByPublishedAtIsNull();
}
