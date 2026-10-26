package dev.jagadeesh.payflow.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One side of a transfer. Every payment writes exactly one DEBIT and one CREDIT of the same amount. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    public enum Direction { DEBIT, CREDIT }

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public static LedgerEntry of(UUID paymentId, UUID accountId, Direction direction,
                                 BigDecimal amount, BigDecimal balanceAfter, Instant at) {
        LedgerEntry e = new LedgerEntry();
        e.id = UUID.randomUUID();
        e.paymentId = paymentId;
        e.accountId = accountId;
        e.direction = direction;
        e.amount = amount;
        e.balanceAfter = balanceAfter;
        e.createdAt = at;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
