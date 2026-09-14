package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PahoMqttCommandTransportTest {

    private final IMqttAsyncClient client = mock(IMqttAsyncClient.class);
    private final IMqttToken connectToken = mock(IMqttToken.class);
    private final IMqttToken disconnectToken = mock(IMqttToken.class);
    private final IMqttDeliveryToken deliveryToken = mock(IMqttDeliveryToken.class);
    private final IMqttToken subscribeToken = mock(IMqttToken.class);
    private final MqttProperties properties = properties(true);

    private PahoMqttCommandTransport transport;

    @BeforeEach
    void setUp() throws MqttException {
        when(client.connect(any(MqttConnectOptions.class))).thenReturn(connectToken);
        when(client.disconnect()).thenReturn(disconnectToken);
        when(client.publish(anyString(), any(byte[].class), anyInt(), anyBoolean()))
                .thenReturn(deliveryToken);
        when(client.subscribe(anyString(), anyInt())).thenReturn(subscribeToken);
        transport = new PahoMqttCommandTransport(properties, client);
    }

    @Test
    void connectsWithAutomaticReconnectCredentialsAndBoundedWait() throws Exception {
        transport.connect();

        ArgumentCaptor<MqttConnectOptions> optionsCaptor = ArgumentCaptor.forClass(MqttConnectOptions.class);
        verify(client).connect(optionsCaptor.capture());
        MqttConnectOptions options = optionsCaptor.getValue();
        assertThat(options.isAutomaticReconnect()).isTrue();
        assertThat(options.isCleanSession()).isTrue();
        assertThat(options.getConnectionTimeout()).isEqualTo(5);
        assertThat(options.getUserName()).isEqualTo("operator");
        assertThat(options.getPassword()).containsExactly("top-secret".toCharArray());
        verify(connectToken).waitForCompletion(5_000L);
    }

    @Test
    void skipsConnectWhenClientIsAlreadyConnected() throws Exception {
        when(client.isConnected()).thenReturn(true);

        transport.connect();

        verify(client, never()).connect(any(MqttConnectOptions.class));
    }

    @Test
    void publishesWithCallerQosRetainAndBoundedWait() throws Exception {
        byte[] payload = "{\"commandId\":\"command-001\"}".getBytes(StandardCharsets.UTF_8);

        transport.publish("animalguard/devices/pi-001/commands", payload, 1, false);

        verify(client).publish("animalguard/devices/pi-001/commands", payload, 1, false);
        verify(deliveryToken).waitForCompletion(5_000L);
    }

    @Test
    void classifiesPahoPublishFailureAsTransportFailure() throws Exception {
        when(client.publish(anyString(), any(byte[].class), anyInt(), anyBoolean()))
                .thenThrow(new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED));

        assertThatThrownBy(() -> transport.publish("topic", new byte[]{1}, 1, false))
                .isInstanceOf(MqttTransportException.class)
                .hasMessageContaining("publish");
    }

    @Test
    void subscribesWithCallerFilterQosAndBoundedWait() throws Exception {
        transport.subscribe("animalguard/devices/+/acks", 1);

        verify(client).subscribe("animalguard/devices/+/acks", 1);
        verify(subscribeToken).waitForCompletion(5_000L);
    }

    @Test
    void classifiesPahoSubscribeFailureAsTransportFailure() throws Exception {
        when(client.subscribe(anyString(), anyInt()))
                .thenThrow(new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED));

        assertThatThrownBy(() -> transport.subscribe("animalguard/devices/+/acks", 1))
                .isInstanceOf(MqttTransportException.class)
                .hasMessageContaining("subscribe");
    }

    @Test
    void delegatesPahoCallbacksWithoutExposingPahoMessageTypes() throws Exception {
        ArgumentCaptor<MqttCallback> pahoCallbackCaptor = ArgumentCaptor.forClass(MqttCallback.class);
        verify(client).setCallback(pahoCallbackCaptor.capture());
        MqttCallbackExtended pahoCallback = (MqttCallbackExtended) pahoCallbackCaptor.getValue();
        MqttCommandTransport.Callback callback = mock(MqttCommandTransport.Callback.class);
        transport.setCallback(callback);
        MqttMessage message = new MqttMessage("payload".getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(true);
        when(deliveryToken.getMessageId()).thenReturn(42);
        RuntimeException connectionFailure = new RuntimeException("connection lost");

        pahoCallback.connectComplete(true, "tcp://broker.internal:1883");
        pahoCallback.connectionLost(connectionFailure);
        pahoCallback.messageArrived("topic", message);
        pahoCallback.deliveryComplete(deliveryToken);

        verify(callback).connectComplete(true, "tcp://broker.internal:1883");
        verify(callback).connectionLost(connectionFailure);
        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(callback).messageArrived(eq("topic"), payloadCaptor.capture(), eq(1), eq(true));
        assertThat(payloadCaptor.getValue()).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
        verify(callback).deliveryComplete(42);
    }

    @Test
    void disconnectsAndWaitsOnlyWhenConnected() throws Exception {
        when(client.isConnected()).thenReturn(true);

        transport.disconnect();

        verify(client).disconnect();
        verify(disconnectToken).waitForCompletion(5_000L);
    }

    private MqttProperties properties(boolean enabled) {
        return new MqttProperties(
                enabled,
                "broker.internal",
                1883,
                "backend-test",
                "operator",
                "top-secret",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMillis(500),
                20
        );
    }
}
