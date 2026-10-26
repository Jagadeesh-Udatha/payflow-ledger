package dev.jagadeesh.payflow.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API view of a payment. {@code replayed} is true when an Idempotency-Key retry returned the original result. */
public record PaymentResult(UUID id, Payment.Status status, UUID sourceAccountId, UUID targetAccountId,
                            BigDecimal amount, String currency, String reference, Instant createdAt,
                            boolean replayed) {

    static PaymentResult of(Payment p, boolean replayed) {
        return new PaymentResult(p.getId(), p.getStatus(), p.getSourceAccountId(), p.getTargetAccountId(),
                p.getAmount(), p.getCurrency(), p.getReference(), p.getCreatedAt(), replayed);
    }
}
