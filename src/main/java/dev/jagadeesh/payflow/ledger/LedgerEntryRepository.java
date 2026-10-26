package dev.jagadeesh.payflow.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    List<LedgerEntry> findByPaymentId(UUID paymentId);

    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.direction = :direction")
    BigDecimal sumByDirection(LedgerEntry.Direction direction);
}
