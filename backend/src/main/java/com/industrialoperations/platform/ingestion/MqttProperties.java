package com.industrialoperations.platform.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MQTT ingestion settings, bound from {@code platform.mqtt}. */
@ConfigurationProperties(prefix = "platform.mqtt")
public record MqttProperties(
        String brokerUrl,
        String clientId,
        String topicFilter,
        int qos) {
}
