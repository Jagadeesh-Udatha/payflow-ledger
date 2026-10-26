package dev.jagadeesh.payflow.common;

/** The Idempotency-Key was already used for a different request body. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key '%s' was already used with a different request".formatted(key));
    }
}
