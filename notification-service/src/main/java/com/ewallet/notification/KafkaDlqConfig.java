package com.ewallet.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaDlqConfig {
    @Bean
    NewTopic walletEventsDlqTopic(@Value("${banking.kafka.events-topic:wallet.events.v1}") String sourceTopic) {
        return new NewTopic(sourceTopic + ".DLQ", 1, (short) 1);
    }

    @Bean
    DefaultErrorHandler walletEventErrorHandler(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${banking.kafka.events-topic:wallet.events.v1}") String sourceTopic
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) -> new TopicPartition(sourceTopic + ".DLQ", record.partition())
        );
        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
    }
}
