package com.ewallet.notification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class DlqReplayService {
    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String sourceTopic;
    private final String dlqTopic;

    public DlqReplayService(
        ConsumerFactory<String, String> consumerFactory,
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${banking.kafka.events-topic:wallet.events.v1}") String sourceTopic
    ) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.sourceTopic = sourceTopic;
        this.dlqTopic = sourceTopic + ".DLQ";
    }

    public List<DlqMessage> inspect(int limit) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlq-inspector", null)) {
            List<TopicPartition> partitions = partitions(consumer);
            if (partitions.isEmpty()) {
                return List.of();
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<DlqMessage> messages = new ArrayList<>();
            while (messages.size() < limit) {
                var records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) {
                    break;
                }
                for (ConsumerRecord<String, String> record : records) {
                    messages.add(toMessage(record));
                    if (messages.size() == limit) {
                        break;
                    }
                }
            }
            return messages;
        }
    }

    public DlqReplayResult replay(DlqReplayRequest request) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlq-replayer", null)) {
            TopicPartition partition = new TopicPartition(dlqTopic, request.partition());
            consumer.assign(List.of(partition));
            consumer.seek(partition, request.offset());
            var records = consumer.poll(Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                if (record.partition() == request.partition() && record.offset() == request.offset()) {
                    ProducerRecord<String, String> replay = new ProducerRecord<>(sourceTopic, record.key(), record.value());
                    record.headers().forEach(header -> replay.headers().add(header));
                    kafkaTemplate.send(replay).get();
                    consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                    return new DlqReplayResult(true, "Replayed DLQ record to " + sourceTopic);
                }
            }
            return new DlqReplayResult(false, "DLQ record not found");
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to replay DLQ record", ex);
        }
    }

    public long depth() {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlq-depth", null)) {
            List<TopicPartition> partitions = partitions(consumer);
            if (partitions.isEmpty()) {
                return 0;
            }
            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            return partitions.stream()
                .mapToLong(partition -> end.getOrDefault(partition, 0L) - beginning.getOrDefault(partition, 0L))
                .sum();
        }
    }

    private List<TopicPartition> partitions(Consumer<String, String> consumer) {
        List<PartitionInfo> infos = consumer.partitionsFor(dlqTopic, Duration.ofSeconds(2));
        if (infos == null) {
            return List.of();
        }
        return infos.stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .sorted(Comparator.comparingInt(TopicPartition::partition))
            .toList();
    }

    private DlqMessage toMessage(ConsumerRecord<String, String> record) {
        return new DlqMessage(
            record.partition(),
            record.offset(),
            record.key(),
            record.value(),
            header(record, "event_id"),
            header(record, "event_type")
        );
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value());
    }
}
