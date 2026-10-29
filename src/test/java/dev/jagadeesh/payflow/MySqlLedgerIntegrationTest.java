package dev.jagadeesh.payflow;

import dev.jagadeesh.payflow.account.Account;
import dev.jagadeesh.payflow.account.AccountRepository;
import dev.jagadeesh.payflow.account.AccountService;
import dev.jagadeesh.payflow.account.OpenAccountRequest;
import dev.jagadeesh.payflow.outbox.OutboxEvent;
import dev.jagadeesh.payflow.outbox.OutboxRepository;
import dev.jagadeesh.payflow.payment.PaymentResult;
import dev.jagadeesh.payflow.payment.PaymentService;
import dev.jagadeesh.payflow.payment.TransferRequest;
import dev.jagadeesh.payflow.report.ReportController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same ledger flow as PaymentServiceTest, but on a real MySQL 8 server: checks schema.sql, CHAR(36) ids,
 * SELECT ... FOR UPDATE row locks and the outbox's FOR UPDATE SKIP LOCKED query. Skipped when Docker isn't running.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "payflow.outbox.relay-enabled=false")
class MySqlLedgerIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired PaymentService payments;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepo;
    @Autowired OutboxRepository outbox;
    @Autowired ReportController reports;
    @Autowired TransactionTemplate tx;

    @Test
    void transferOnMySqlKeepsTheLedgerBalancedAndQueuesAnEvent() {
        Account alice = accounts.open(new OpenAccountRequest("Alice", "INR", new BigDecimal("1000.00")));
        Account bob = accounts.open(new OpenAccountRequest("Bob", "INR", BigDecimal.ZERO));

        PaymentResult result = payments.transfer(UUID.randomUUID().toString(),
                new TransferRequest(alice.getId(), bob.getId(), new BigDecimal("250.50"), "rent"));

        assertThat(accountRepo.findById(alice.getId()).orElseThrow().getBalance()).isEqualByComparingTo("749.50");
        assertThat(accountRepo.findById(bob.getId()).orElseThrow().getBalance()).isEqualByComparingTo("250.50");
        assertThat(reports.trialBalance().balanced()).isTrue();
        assertThat(outbox.findByAggregateId(result.id())).hasSize(1);
        // The relay's locking query must be valid MySQL 8 syntax.
        List<OutboxEvent> pending = tx.execute(s -> outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc());
        assertThat(pending).isNotEmpty();
    }
}
