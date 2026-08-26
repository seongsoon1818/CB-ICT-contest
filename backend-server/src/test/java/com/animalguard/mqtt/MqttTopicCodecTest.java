package com.animalguard.mqtt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttTopicCodecTest {

    @Test
    void preservesRfc3986UnreservedAscii() {
        assertThat(MqttTopicCodec.encodeSegment("pi-001._~AZaz09")).isEqualTo("pi-001._~AZaz09");
    }

    @Test
    void percentEncodesTopicSeparatorsWildcardsPercentAndSpaceWithoutFormPlus() {
        assertThat(MqttTopicCodec.encodeSegment("pi/+#% 001"))
                .isEqualTo("pi%2F%2B%23%25%20001");
    }

    @Test
    void roundTripsUtf8DeviceId() {
        String deviceId = "장치/서울+1";

        String encoded = MqttTopicCodec.encodeSegment(deviceId);

        assertThat(encoded).doesNotContain("/", "+", "#");
        assertThat(MqttTopicCodec.decodeSegment(encoded)).isEqualTo(deviceId);
        assertThat(MqttTopicCodec.commandTopic(deviceId))
                .isEqualTo("animalguard/devices/" + encoded + "/commands");
    }

    @Test
    void rejectsMalformedPercentEscape() {
        assertThatThrownBy(() -> MqttTopicCodec.decodeSegment("pi%2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percent escape");
        assertThatThrownBy(() -> MqttTopicCodec.decodeSegment("pi%GG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percent escape");
    }

    @Test
    void rejectsInvalidUtf8Bytes() {
        assertThatThrownBy(() -> MqttTopicCodec.decodeSegment("%C3%28"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void decodesDeviceIdFromExactAckAndStatusTopics() {
        assertThat(MqttTopicCodec.deviceIdFromAckTopic(
                "animalguard/devices/%EC%9E%A5%EC%B9%98%2F1/acks"
        )).isEqualTo("장치/1");
        assertThat(MqttTopicCodec.deviceIdFromStatusTopic(
                "animalguard/devices/pi-001/status"
        )).isEqualTo("pi-001");
        assertThat(MqttTopicCodec.ACK_TOPIC_FILTER).isEqualTo("animalguard/devices/+/acks");
        assertThat(MqttTopicCodec.STATUS_TOPIC_FILTER).isEqualTo("animalguard/devices/+/status");
    }

    @Test
    void rejectsUnexpectedOrMultiSegmentInboundTopic() {
        assertThatThrownBy(() -> MqttTopicCodec.deviceIdFromAckTopic(
                "animalguard/devices/pi/001/acks"
        )).isInstanceOf(MqttInboundContractException.class);
        assertThatThrownBy(() -> MqttTopicCodec.deviceIdFromStatusTopic(
                "animalguard/devices/pi-001/acks"
        )).isInstanceOf(MqttInboundContractException.class);
    }
}
