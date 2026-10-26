package dev.jagadeesh.payflow;

import dev.jagadeesh.payflow.account.Account;
import dev.jagadeesh.payflow.account.AccountRepository;
import dev.jagadeesh.payflow.account.AccountService;
import dev.jagadeesh.payflow.account.OpenAccountRequest;
import dev.jagadeesh.payflow.common.BusinessRuleException;
import dev.jagadeesh.payflow.common.IdempotencyConflictException;
import dev.jagadeesh.payflow.ledger.LedgerEntry;
import dev.jagadeesh.payflow.ledger.LedgerEntryRepository;
import dev.jagadeesh.payflow.outbox.OutboxRepository;
import dev.jagadeesh.payflow.payment.PaymentResult;
import dev.jagadeesh.payflow.payment.PaymentService;
import dev.jagadeesh.payflow.payment.TransferRequest;
import dev.jagadeesh.payflow.report.ReportController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceTest {

    @Autowired PaymentService payments;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepo;
    @Autowired LedgerEntryRepository ledger;
    @Autowired OutboxRepository outbox;
    @Autowired ReportController reports;

    @Test
    void transferMovesMoneyAndWritesBalancedEntriesAndOutboxEvent() {
        Account alice = open("Alice", "1000.00");
        Account bob = open("Bob", "0");

        PaymentResult result = payments.transfer(key(), new TransferRequest(alice.getId(), bob.getId(), money("250.50"), "rent"));

        assertThat(balance(alice)).isEqualByComparingTo("749.50");
        assertThat(balance(bob)).isEqualByComparingTo("250.50");
        List<LedgerEntry> entries = ledger.findByPaymentId(result.id());
        assertThat(entries).extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(LedgerEntry.Direction.DEBIT, LedgerEntry.Direction.CREDIT);
        assertThat(outbox.findByAggregateId(result.id())).hasSize(1);
        assertThat(reports.trialBalance().balanced()).isTrue();
    }

    @Test
    void retryWithSameKeyReturnsOriginalPaymentWithoutChargingTwice() {
        Account alice = open("Alice", "500.00");
        Account bob = open("Bob", "0");
        String key = key();
        TransferRequest request = new TransferRequest(alice.getId(), bob.getId(), money("100"), null);

        PaymentResult first = payments.transfer(key, request);
        PaymentResult retry = payments.transfer(key, new TransferRequest(alice.getId(), bob.getId(), money("100.00"), null));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(balance(alice)).isEqualByComparingTo("400.00");
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        Account alice = open("Alice", "500.00");
        Account bob = open("Bob", "0");
        String key = key();
        payments.transfer(key, new TransferRequest(alice.getId(), bob.getId(), money("100"), null));

        assertThatThrownBy(() -> payments.transfer(key, new TransferRequest(alice.getId(), bob.getId(), money("999"), null)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void insufficientFundsRollsBackEverything() {
        Account alice = open("Alice", "50.00");
        Account bob = open("Bob", "0");

        assertThatThrownBy(() -> payments.transfer(key(), new TransferRequest(alice.getId(), bob.getId(), money("50.01"), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("needs 50.01");

        assertThat(balance(alice)).isEqualByComparingTo("50.00");
        assertThat(balance(bob)).isEqualByComparingTo("0");
    }

    @Test
    void frozenAccountCannotSend() {
        Account alice = open("Alice", "100.00");
        Account bob = open("Bob", "0");
        accounts.freeze(alice.getId());

        assertThatThrownBy(() -> payments.transfer(key(), new TransferRequest(alice.getId(), bob.getId(), money("1"), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("FROZEN");
    }

    @Test
    void concurrentTransfersNeverOverdraw() throws Exception {
        Account alice = open("Alice", "100.00");
        Account bob = open("Bob", "0");
        int threads = 20;  // 20 x 10.00 requested, only 10 can succeed
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(threads, i -> {
            try {
                payments.transfer(key(), new TransferRequest(alice.getId(), bob.getId(), money("10.00"), null));
            } catch (BusinessRuleException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(rejected.get()).isEqualTo(10);
        assertThat(balance(alice)).isEqualByComparingTo("0");
        assertThat(balance(bob)).isEqualByComparingTo("100.00");
        assertThat(reports.trialBalance().balanced()).isTrue();
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        Account alice = open("Alice", "1000.00");
        Account bob = open("Bob", "1000.00");

        runConcurrently(20, i -> {
            boolean even = i % 2 == 0;
            payments.transfer(key(), new TransferRequest(even ? alice.getId() : bob.getId(),
                    even ? bob.getId() : alice.getId(), money("5.00"), null));
        });

        assertThat(balance(alice).add(balance(bob))).isEqualByComparingTo("2000.00");
        assertThat(balance(alice)).isEqualByComparingTo("1000.00");
    }

    private interface Task {
        void run(int i) throws Exception;
    }

    private static void runConcurrently(int threads, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                task.run(n);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
    }

    private Account open(String owner, String deposit) {
        return accounts.open(new OpenAccountRequest(owner, "INR", money(deposit)));
    }

    private BigDecimal balance(Account a) {
        return accountRepo.findById(a.getId()).orElseThrow().getBalance();
    }

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
