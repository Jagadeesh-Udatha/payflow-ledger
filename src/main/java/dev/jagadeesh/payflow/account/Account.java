package dev.jagadeesh.payflow.account;

import dev.jagadeesh.payflow.common.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account")
public class Account {

    public enum Type { CUSTOMER, SETTLEMENT }

    public enum Status { ACTIVE, FROZEN, CLOSED }

    @Id
    private UUID id;

    @Column(name = "owner_name", nullable = false, length = 120)
    private String ownerName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
    }

    public static Account openCustomer(String ownerName, String currency) {
        Account a = new Account();
        a.id = UUID.randomUUID();
        a.ownerName = ownerName;
        a.currency = currency;
        a.type = Type.CUSTOMER;
        a.status = Status.ACTIVE;
        a.balance = BigDecimal.ZERO;
        a.createdAt = Instant.now();
        return a;
    }

    /** Money out. Customer accounts may not go below zero; settlement accounts may. */
    public void debit(BigDecimal amount) {
        requireActive();
        if (type == Type.CUSTOMER && balance.compareTo(amount) < 0) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS",
                    "Account %s has %s %s, needs %s".formatted(id, balance.toPlainString(), currency, amount.toPlainString()));
        }
        balance = balance.subtract(amount);
    }

    /** Money in. */
    public void credit(BigDecimal amount) {
        requireActive();
        balance = balance.add(amount);
    }

    public void freeze() {
        status = Status.FROZEN;
    }

    private void requireActive() {
        if (status != Status.ACTIVE) {
            throw new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account %s is %s".formatted(id, status));
        }
    }

    public UUID getId() { return id; }
    public String getOwnerName() { return ownerName; }
    public String getCurrency() { return currency; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public BigDecimal getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }
}
