package com.industrialoperations.platform.ingestion;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Owns the MQTT connection and subscription. This class deals with transport
 * concerns only:
 * telemetry contract validation and event creation belong to the ingestion
 * adapter.
 */
@Component
@ConditionalOnProperty(prefix = "platform.mqtt", name = "enabled", matchIfMissing = true)
public class MqttTelemetryClient implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttTelemetryClient.class);

    private final MqttProperties mqttProperties;

    private final MqttIngestionAdapter ingestionAdapter;

    private MqttClient client;

    public MqttTelemetryClient(MqttProperties mqttProperties, MqttIngestionAdapter ingestionAdapter) {
        this.mqttProperties = mqttProperties;
        this.ingestionAdapter = ingestionAdapter;
    }

    @PostConstruct
    void start() throws MqttException {
        client = new MqttClient(mqttProperties.brokerUrl(), mqttProperties.clientId(), new MemoryPersistence());
        client.setCallback(this);

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);

        log.info("Connecting to MQTT broker [{}] as client [{}]",
                mqttProperties.brokerUrl(), mqttProperties.clientId());
        client.connect(options);
    }

    @PreDestroy
    void stop() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            log.warn("Failed to close the MQTT client cleanly", e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        log.info("MQTT connection established [broker={}, reconnect={}]", serverUri, reconnect);
        subscribe();
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost; automatic reconnect is enabled", cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            ingestionAdapter.handle(topic, message.getPayload());
        } catch (InvalidMqttMessageException e) {
            log.warn("Discarding invalid MQTT message on topic [{}]", topic, e);
        } catch (RuntimeException e) {
            log.error("Failed to process MQTT message: [{}]",topic, e);
            throw e;
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // This client only subscribes; it never publishes, so no delivery tokens are
        // expected.
    }

    private void subscribe() {
        try {
            client.subscribe(mqttProperties.topicFilter(), mqttProperties.qos());
            log.info("Subscribed to MQTT topic filter [{}] with QoS {}",
                    mqttProperties.topicFilter(), mqttProperties.qos());
        } catch (MqttException e) {
            log.error("Failed to subscribe to MQTT topic filter [{}]", mqttProperties.topicFilter(), e);
        }
    }
}
