package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

@Slf4j
public final class PahoMqttCommandTransport implements MqttCommandTransport {

    private static final Callback NO_OP_CALLBACK = new Callback() {
    };

    private final MqttProperties properties;
    private final IMqttAsyncClient client;
    private volatile Callback callback = NO_OP_CALLBACK;

    public PahoMqttCommandTransport(MqttProperties properties) {
        this(properties, createClient(properties));
    }

    PahoMqttCommandTransport(MqttProperties properties, IMqttAsyncClient client) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.client.setCallback(new PahoCallback());
    }

    @Override
    public synchronized void connect() {
        if (client.isConnected()) {
            return;
        }
        try {
            IMqttToken token = client.connect(connectOptions());
            token.waitForCompletion(timeoutMillis(properties.connectTimeout()));
        } catch (MqttException exception) {
            throw new MqttTransportException("MQTT connect failed: " + safeReason(exception), exception);
        }
    }

    @Override
    public synchronized void disconnect() {
        if (!client.isConnected()) {
            return;
        }
        try {
            IMqttToken token = client.disconnect();
            token.waitForCompletion(timeoutMillis(properties.connectTimeout()));
        } catch (MqttException exception) {
            throw new MqttTransportException("MQTT disconnect failed: " + safeReason(exception), exception);
        }
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public void publish(String topic, byte[] payload, int qos, boolean retained) {
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            IMqttDeliveryToken token = client.publish(topic, Arrays.copyOf(payload, payload.length), qos, retained);
            token.waitForCompletion(timeoutMillis(properties.publishTimeout()));
        } catch (MqttException exception) {
            throw new MqttTransportException("MQTT publish failed: " + safeReason(exception), exception);
        }
    }

    @Override
    public void setCallback(Callback callback) {
        this.callback = Objects.requireNonNull(callback, "callback must not be null");
    }

    @Override
    public void close() {
        try {
            disconnect();
        } catch (MqttTransportException exception) {
            log.warn("MQTT disconnect during shutdown failed: reason={}", exception.getMessage());
        }
        try {
            client.close();
        } catch (MqttException exception) {
            log.warn("MQTT client close failed: reason={}", safeReason(exception));
        }
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(timeoutSeconds(properties.connectTimeout()));
        if (!properties.username().isBlank()) {
            options.setUserName(properties.username());
        }
        if (!properties.password().isBlank()) {
            options.setPassword(properties.password().toCharArray());
        }
        return options;
    }

    private static IMqttAsyncClient createClient(MqttProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        String serverUri = "tcp://" + properties.host() + ":" + properties.port();
        try {
            return new MqttAsyncClient(serverUri, properties.clientId(), new MemoryPersistence());
        } catch (MqttException exception) {
            throw new IllegalStateException("MQTT client configuration is invalid", exception);
        }
    }

    private static long timeoutMillis(Duration timeout) {
        return Math.max(1L, timeout.toMillis());
    }

    private static int timeoutSeconds(Duration timeout) {
        long milliseconds = timeoutMillis(timeout);
        long seconds = (milliseconds + 999L) / 1_000L;
        return Math.toIntExact(seconds);
    }

    private static String safeReason(MqttException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "reasonCode=" + exception.getReasonCode()
                : message;
    }

    private final class PahoCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            callback.connectComplete(reconnect, serverUri);
        }

        @Override
        public void connectionLost(Throwable cause) {
            callback.connectionLost(cause);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            byte[] payload = message.getPayload();
            callback.messageArrived(
                    topic,
                    Arrays.copyOf(payload, payload.length),
                    message.getQos(),
                    message.isRetained()
            );
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            callback.deliveryComplete(token.getMessageId());
        }
    }
}
