package dev.jagadeesh.payflow.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment")
public class Payment {

    public enum Status { COMPLETED }

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    /** SHA-256 of the request body, so a reused key with a different body is rejected instead of replayed. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(length = 140)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    static Payment completed(String idempotencyKey, String requestHash, UUID source, UUID target,
                             BigDecimal amount, String currency, String reference, Instant at) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.idempotencyKey = idempotencyKey;
        p.requestHash = requestHash;
        p.sourceAccountId = source;
        p.targetAccountId = target;
        p.amount = amount;
        p.currency = currency;
        p.status = Status.COMPLETED;
        p.reference = reference;
        p.createdAt = at;
        return p;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getTargetAccountId() { return targetAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getReference() { return reference; }
    public Instant getCreatedAt() { return createdAt; }
}
