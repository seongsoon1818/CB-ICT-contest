package com.animalguard.mqtt;

public interface MqttCommandTransport extends AutoCloseable {

    void connect();

    void disconnect();

    boolean isConnected();

    void publish(String topic, byte[] payload, int qos, boolean retained);

    void subscribe(String topicFilter, int qos);

    void setCallback(Callback callback);

    @Override
    void close();

    interface Callback {

        default void connectComplete(boolean reconnect, String serverUri) {
        }

        default void connectionLost(Throwable cause) {
        }

        default void messageArrived(String topic, byte[] payload, int qos, boolean retained) {
        }

        default void deliveryComplete(int messageId) {
        }
    }
}
