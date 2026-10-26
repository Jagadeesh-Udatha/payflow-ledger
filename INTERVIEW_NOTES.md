# Interview notes: PayFlow Ledger

Read this before any fintech / payments interview. Each section is a question you are likely to get, the answer, and where it lives in the code.

## 1. "How do you stop a payment from being processed twice?"

**Idempotency keys.** The client generates a UUID per payment *intent* and sends it as `Idempotency-Key`. Retries reuse it.

- `payment.idempotency_key` has a **unique constraint**: the database is the final guard.
- `PaymentService.transfer` first looks the key up. Found + same request hash → return the stored payment (`replayed=true`). Found + different hash → `409 IDEMPOTENCY_KEY_REUSED`.
- Two requests with the same key at the same instant both miss the lookup; one commits, the other hits the unique constraint. `PaymentController` catches `DataIntegrityViolationException` and returns the winner's result.
- The request hash (`TransferRequest.fingerprint`) normalises the amount (`100` and `100.00` hash the same).

Follow-up: *Why not just check-then-insert?* Because check-then-insert is a race; only the unique index makes it safe.

## 2. "How do you prevent overdrafts under concurrency?"

**Pessimistic locking**: `AccountRepository.lockById` is `@Lock(PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE`. The second transaction waits until the first commits, then reads the new balance.

Alternatives you should be able to compare:

| Approach | Pros | Cons |
|---|---|---|
| Pessimistic lock (used here) | Simple, correct, no retries | Hot accounts serialize; needs lock timeout |
| Optimistic lock (`@Version`) | No blocking | Conflicts → retries; bad for hot accounts |
| Conditional update `UPDATE account SET balance = balance - ? WHERE id = ? AND balance >= ?` | One round trip, very fast | Logic moves into SQL; still need ledger rows |

The entity also has `@Version`, so any write path that forgets the lock still fails safely instead of losing an update.

Test: `PaymentServiceTest.concurrentTransfersNeverOverdraw` (20 threads, 10 must fail).

## 3. "What about deadlocks?"

A→B locks A then B; B→A locks B then A → classic cycle. Fix: **always lock in a global order** (lower UUID first). Test: `oppositeTransfersDoNotDeadlock`.

## 4. "Explain double-entry bookkeeping."

Every movement has two equal sides: DEBIT on the source (money out), CREDIT on the target (money in). Invariants:

1. Σ debits = Σ credits.
2. Opening deposits come from a **settlement account** (allowed to go negative), so Σ all balances = 0.

`GET /api/reports/trial-balance` checks both. Ledger rows are **append-only**: you never update or delete an entry; a refund is a new reverse payment.

`balance_after` on each entry makes statements cheap and lets you audit any point in time.

## 5. "Why BigDecimal, and what scale?"

`double` can't represent 0.1 exactly. Money is `BigDecimal` / `numeric(19,4)`. Compare with `compareTo`, not `equals` (`100.0` ≠ `100.00` with `equals`). API accepts at most 2 decimals (`@Digits(fraction = 2)`).

## 6. "How do you publish events reliably? (dual-write problem)"

Writing to the DB and then to Kafka can fail between the two. **Transactional outbox**:

1. In the same DB transaction as the payment, insert an `outbox_event` row.
2. `OutboxRelay` polls unpublished rows in order, sends to Kafka (`acks=all`, idempotent producer), marks them published.
3. Delivery is **at-least-once**: a crash after send but before commit re-sends. Consumers dedupe on the `event-id` header.
4. Kafka key = payment id → all events for one payment go to one partition → ordered.

Scaling the relay to several instances: use `SELECT … FOR UPDATE SKIP LOCKED` (PostgreSQL) so instances take different rows, or switch to CDC (Debezium reads the WAL).

## 7. "How is the API secured?"

- Stateless JWT (HS256) issued by `POST /api/auth/token`, verified by Spring Security's resource server (`SecurityConfig`).
- Scopes → authorities: `payments:read` (auditor), `payments:write` (ops). GETs need read or write; everything else needs write.
- Passwords stored with the delegating encoder (bcrypt).
- CORS limited to the console origin; CSRF disabled because there are no cookies.

In production: asymmetric keys (RS256) from an identity provider, key rotation, short TTL + refresh tokens.

## 8. "How do you handle errors?"

`ApiExceptionHandler` maps exceptions to RFC 7807 `ProblemDetail` with a stable `code`: 400 validation (with per-field messages), 404, 409 key reuse, 422 business rules (insufficient funds, frozen account, currency mismatch).

## 9. "How would you run this in production?"

- Flyway migrations, `ddl-auto=validate`.
- Indexes: `(account_id, created_at)` for statements, unique on idempotency key, `(published_at, created_at)` for the relay.
- Health probes (`/actuator/health/liveness`, `/readiness`) for Kubernetes; Prometheus metrics: `payflow_payments_completed_total`, `payflow_outbox_pending`.
- Multi-stage Dockerfile running as non-root; GitHub Actions runs tests + coverage and builds the console.

## 10. "What would you add next?"

- Holds/authorisations (reserve funds, capture later).
- Multi-currency with FX and per-currency settlement.
- Partitioning `ledger_entry` by month; read replicas for statements.
- Rate limiting per client; audit log of who did what.
