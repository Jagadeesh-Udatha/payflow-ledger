package dev.jagadeesh.payflow;

import dev.jagadeesh.payflow.account.Account;
import dev.jagadeesh.payflow.account.AccountService;
import dev.jagadeesh.payflow.account.OpenAccountRequest;
import dev.jagadeesh.payflow.outbox.OutboxRelay;
import dev.jagadeesh.payflow.outbox.OutboxRepository;
import dev.jagadeesh.payflow.payment.PaymentResult;
import dev.jagadeesh.payflow.payment.PaymentService;
import dev.jagadeesh.payflow.payment.TransferRequest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "payflow.outbox.relay-enabled=true",
        "payflow.outbox.poll-ms=600000"  // the test triggers the relay itself
})
@EmbeddedKafka(partitions = 1, topics = "payments.events")
@ActiveProfiles("test")
class OutboxRelayKafkaTest {

    @Autowired AccountService accounts;
    @Autowired PaymentService payments;
    @Autowired OutboxRelay relay;
    @Autowired OutboxRepository outbox;
    @Autowired EmbeddedKafkaBroker broker;

    @Test
    void relayPublishesCommittedPaymentAndMarksItSent() {
        Account alice = accounts.open(new OpenAccountRequest("Alice", "INR", new BigDecimal("300")));
        Account bob = accounts.open(new OpenAccountRequest("Bob", "INR", BigDecimal.ZERO));
        PaymentResult payment = payments.transfer(UUID.randomUUID().toString(),
                new TransferRequest(alice.getId(), bob.getId(), new BigDecimal("75.25"), "kafka-test"));

        relay.publishPending();

        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("outbox-test", "true", broker),
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "payments.events");
            var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
            ConsumerRecord<String, String> match = null;
            for (ConsumerRecord<String, String> r : records) {
                if (r.key().equals(payment.id().toString())) {
                    match = r;
                }
            }
            assertThat(match).isNotNull();
            assertThat(match.value()).contains("\"amount\":\"75.25\"").contains("kafka-test");
            assertThat(new String(match.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                    .isEqualTo("PaymentCompleted");
        }
        assertThat(outbox.findByAggregateId(payment.id())).allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
    }
}
