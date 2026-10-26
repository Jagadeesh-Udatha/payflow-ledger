package dev.jagadeesh.payflow.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox and publishes pending events to Kafka in creation order, keyed by aggregate id so all events for
 * one payment land on the same partition. Delivery is at-least-once: if the app dies after the send but before the
 * commit, the event is sent again, so consumers must de-duplicate on the event id header.
 */
@Component
@ConditionalOnProperty(name = "payflow.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Counter published;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       @Value("${payflow.outbox.topic}") String topic, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.topic = topic;
        this.published = Counter.builder("payflow.outbox.published").register(meters);
        Gauge.builder("payflow.outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNull).register(meters);
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : batch) {
            try {
                var record = new ProducerRecord<>(topic, event.getAggregateId().toString(), event.getPayload());
                record.headers().add("event-id", event.getId().toString().getBytes(StandardCharsets.UTF_8));
                record.headers().add("event-type", event.getEventType().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(5, TimeUnit.SECONDS);
                event.markPublished(Instant.now());
                published.increment();
            } catch (Exception e) {
                // Stop at the first failure to keep ordering; the rest are retried on the next poll.
                log.warn("Outbox publish failed for event {}, will retry: {}", event.getId(), e.getMessage());
                return;
            }
        }
    }
}
