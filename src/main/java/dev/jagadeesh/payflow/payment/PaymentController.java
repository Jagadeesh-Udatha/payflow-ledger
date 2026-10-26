package dev.jagadeesh.payflow.payment;

import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * Creates a transfer. Clients send a unique Idempotency-Key per payment and reuse it on retries:
     * the first call returns 201, a retry returns 200 with the original payment and Idempotent-Replay: true.
     */
    @PostMapping
    public ResponseEntity<PaymentResult> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody TransferRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 64 characters");
        }
        PaymentResult result;
        try {
            result = service.transfer(idempotencyKey, request);
        } catch (DataIntegrityViolationException race) {
            // Two concurrent requests with the same key: the unique index let one commit; return its result.
            result = service.findReplay(idempotencyKey, request).orElseThrow(() -> race);
        }
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replay", String.valueOf(result.replayed()))
                .body(result);
    }

    @GetMapping("/{id}")
    public PaymentResult get(@PathVariable UUID id) {
        return service.get(id);
    }
}
