package com.animalguard.mqtt;

import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceOperationalStatus;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MqttInboundMessageRouterTest {

    private final DeviceCommandAckHandler ackHandler = mock(DeviceCommandAckHandler.class);
    private final DeviceStatusReportService statusService = mock(DeviceStatusReportService.class);
    private final MqttInboundMessageRouter router = new MqttInboundMessageRouter(
            new MqttAckParser(JsonMapper.builder().build()),
            new MqttDeviceStatusParser(JsonMapper.builder().build()),
            ackHandler,
            statusService
    );

    @Test
    void decodesAckTopicAndConsumesUnknownCommandWithoutThrowing() {
        when(ackHandler.handle(any(MqttAckMessage.class), eq("장치/1")))
                .thenReturn(new AckHandlingResult(
                        AckHandlingOutcome.UNKNOWN_COMMAND,
                        null,
                        false
                ));

        assertThatCode(() -> router.route(
                "animalguard/devices/%EC%9E%A5%EC%B9%98%2F1/acks",
                bytes("""
                        {"commandId":"command-unknown","deviceId":"장치/1","status":"ACKNOWLEDGED",
                         "acknowledgedAt":"2026-08-26T00:00:04Z"}
                        """),
                1,
                false
        )).doesNotThrowAnyException();

        verify(ackHandler).handle(any(MqttAckMessage.class), eq("장치/1"));
        verifyNoInteractions(statusService);
    }

    @Test
    void routesStrictDeviceStatus() {
        when(statusService.record(any(MqttDeviceStatusMessage.class), eq("pi-001")))
                .thenReturn(DeviceStatusHandlingOutcome.STORED);

        router.route(
                "animalguard/devices/pi-001/status",
                bytes("""
                        {"deviceId":"pi-001","status":"DEGRADED","reportedAt":"2026-08-26T00:00:04Z",
                         "firmwareVersion":"firmware-v1"}
                        """),
                1,
                false
        );

        verify(statusService).record(
                org.mockito.ArgumentMatchers.argThat(message ->
                        message.status() == DeviceOperationalStatus.DEGRADED),
                eq("pi-001")
        );
        verifyNoInteractions(ackHandler);
    }

    @Test
    void contractErrorDoesNotReachHandlersOrEscapeSubscriberCallback() {
        assertThatCode(() -> router.route(
                "animalguard/devices/pi-001/acks",
                bytes("""
                        {"commandId":"command-001","deviceId":"pi-001","status":"EXECUTED",
                         "executedAt":"2026-08-26T00:00:04Z","extra":true}
                        """),
                1,
                false
        )).doesNotThrowAnyException();

        verify(ackHandler, never()).handle(any(), any());
        verifyNoInteractions(statusService);
    }

    @Test
    void secondOptimisticConflictResultIsConsumedWithoutException() {
        when(ackHandler.handle(any(MqttAckMessage.class), eq("pi-001")))
                .thenReturn(new AckHandlingResult(
                        AckHandlingOutcome.OPTIMISTIC_LOCK_ABORTED,
                        DeviceCommandStatus.PUBLISHED,
                        true
                ));

        assertThatCode(() -> router.route(
                "animalguard/devices/pi-001/acks",
                bytes("""
                        {"commandId":"command-001","deviceId":"pi-001","status":"ACKNOWLEDGED",
                         "acknowledgedAt":"2026-08-26T00:00:04Z"}
                        """),
                1,
                false
        )).doesNotThrowAnyException();
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
