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
}
