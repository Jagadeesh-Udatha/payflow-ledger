package dev.jagadeesh.payflow.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jagadeesh.payflow.account.Account;
import dev.jagadeesh.payflow.account.AccountRepository;
import dev.jagadeesh.payflow.common.BusinessRuleException;
import dev.jagadeesh.payflow.common.IdempotencyConflictException;
import dev.jagadeesh.payflow.common.NotFoundException;
import dev.jagadeesh.payflow.ledger.LedgerEntry;
import dev.jagadeesh.payflow.ledger.LedgerEntryRepository;
import dev.jagadeesh.payflow.outbox.OutboxEvent;
import dev.jagadeesh.payflow.outbox.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final AccountRepository accounts;
    private final PaymentRepository payments;
    private final LedgerEntryRepository ledger;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public PaymentService(AccountRepository accounts, PaymentRepository payments, LedgerEntryRepository ledger,
                          OutboxRepository outbox, ObjectMapper json, MeterRegistry meters) {
        this.accounts = accounts;
        this.payments = payments;
        this.ledger = ledger;
        this.outbox = outbox;
        this.json = json;
        this.meters = meters;
    }

    /**
     * Moves money between two accounts in one transaction: both balances, two ledger entries, the payment row and
     * an outbox event all commit together or not at all.
     */
    @Transactional
    public PaymentResult transfer(String idempotencyKey, TransferRequest request) {
        Optional<PaymentResult> replay = findReplay(idempotencyKey, request);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw new IllegalArgumentException("Source and target accounts must differ");
        }

        // Lock both rows in a fixed (id) order so two opposite transfers A->B and B->A cannot deadlock.
        UUID first = min(request.sourceAccountId(), request.targetAccountId());
        UUID second = first.equals(request.sourceAccountId()) ? request.targetAccountId() : request.sourceAccountId();
        Account a = lock(first);
        Account b = lock(second);
        Account source = a.getId().equals(request.sourceAccountId()) ? a : b;
        Account target = source == a ? b : a;

        if (!source.getCurrency().equals(target.getCurrency())) {
            throw new BusinessRuleException("CURRENCY_MISMATCH",
                    "Cannot move %s to a %s account".formatted(source.getCurrency(), target.getCurrency()));
        }

        source.debit(request.amount());
        target.credit(request.amount());

        Instant now = Instant.now();
        Payment payment = Payment.completed(idempotencyKey, request.fingerprint(), source.getId(), target.getId(),
                request.amount(), source.getCurrency(), request.reference(), now);
        payments.save(payment);
        ledger.save(LedgerEntry.of(payment.getId(), source.getId(), LedgerEntry.Direction.DEBIT,
                request.amount(), source.getBalance(), now));
        ledger.save(LedgerEntry.of(payment.getId(), target.getId(), LedgerEntry.Direction.CREDIT,
                request.amount(), target.getBalance(), now));
        outbox.save(OutboxEvent.of("Payment", payment.getId(), "PaymentCompleted", toJson(payment)));

        meters.counter("payflow.payments.completed", "currency", payment.getCurrency()).increment();
        return PaymentResult.of(payment, false);
    }

    /** The stored result for a key already used with the same body, or a 409 if the body differs. */
    @Transactional(readOnly = true)
    public Optional<PaymentResult> findReplay(String idempotencyKey, TransferRequest request) {
        return payments.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.getRequestHash().equals(request.fingerprint())) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            meters.counter("payflow.payments.replayed").increment();
            return PaymentResult.of(existing, true);
        });
    }

    @Transactional(readOnly = true)
    public PaymentResult get(UUID id) {
        return payments.findById(id).map(p -> PaymentResult.of(p, false))
                .orElseThrow(() -> new NotFoundException("Payment %s not found".formatted(id)));
    }

    private Account lock(UUID id) {
        return accounts.lockById(id).orElseThrow(() -> new NotFoundException("Account %s not found".formatted(id)));
    }

    private static UUID min(UUID x, UUID y) {
        return x.compareTo(y) <= 0 ? x : y;
    }

    private String toJson(Payment p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentId", p.getId());
        body.put("sourceAccountId", p.getSourceAccountId());
        body.put("targetAccountId", p.getTargetAccountId());
        body.put("amount", p.getAmount().toPlainString());
        body.put("currency", p.getCurrency());
        body.put("reference", p.getReference());
        body.put("createdAt", p.getCreatedAt().toString());
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
