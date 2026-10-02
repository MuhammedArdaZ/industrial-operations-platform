package com.industrialoperations.platform.telemetry;

import java.time.Duration;
import java.util.List;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class TelemetryDltReplayService {

    private final ConsumerFactory<String, TelemetryReceivedEvent> consumerFactory;
    private final KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate;
    private final String telemetryTopic;

    public TelemetryDltReplayService(ConsumerFactory<String, TelemetryReceivedEvent> consumerFactory,
            KafkaTemplate<String, TelemetryReceivedEvent> kafkaTemplate,
            @Value("${platform.kafka.telemetry-topic}") String telemetryTopic) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.telemetryTopic = telemetryTopic;
    }

    public int replay(int maxRecords) {
        if (maxRecords <= 0) {
            return 0;
        }

        try (var consumer = consumerFactory.createConsumer("telemetry-dlt-replay-group", "replay")) {
            consumer.subscribe(List.of(telemetryTopic + "-dlt"));

            ConsumerRecords<String, TelemetryReceivedEvent> records = consumer.poll(Duration.ofSeconds(3));

            int count = 0;
            for (ConsumerRecord<String, TelemetryReceivedEvent> record : records) {
                if (count >= maxRecords)
                    break;
                
                kafkaTemplate.send(telemetryTopic, record.key(), record.value());
                count++;
            }
            if (count > 0) {
                consumer.commitSync();
            }

            return count;
        }
    }
}
