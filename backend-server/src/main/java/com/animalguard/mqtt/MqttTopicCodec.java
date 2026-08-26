package com.animalguard.mqtt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class MqttTopicCodec {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private MqttTopicCodec() {
    }

    public static String commandTopic(String deviceId) {
        return "animalguard/devices/" + encodeSegment(deviceId) + "/commands";
    }

    public static String encodeSegment(String value) {
        requireValue(value);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte rawByte : bytes) {
            int unsigned = rawByte & 0xFF;
            if (isUnreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(HEX[unsigned >>> 4]);
                encoded.append(HEX[unsigned & 0x0F]);
            }
        }
        return encoded.toString();
    }

    public static String decodeSegment(String value) {
        requireValue(value);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '%') {
                if (index + 2 >= value.length()) {
                    throw malformedEscape(value, index);
                }
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw malformedEscape(value, index);
                }
                decoded.write((high << 4) | low);
                index += 2;
            } else if (current <= 0x7F) {
                decoded.write(current);
            } else {
                throw new IllegalArgumentException("encoded topic segment must contain ASCII characters only");
            }
        }

        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("topic segment does not contain valid UTF-8", exception);
        }
    }

    private static boolean isUnreserved(int value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    private static void requireValue(String value) {
        Objects.requireNonNull(value, "topic segment must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("topic segment must not be blank");
        }
    }

    private static IllegalArgumentException malformedEscape(String value, int index) {
        return new IllegalArgumentException(
                "invalid percent escape at index " + index + " in topic segment " + value
        );
    }
}
