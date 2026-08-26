package com.animalguard.mqtt;

import com.animalguard.domain.DeviceOperationalStatus;
import com.animalguard.domain.DeviceStatus;
import com.animalguard.repository.DeviceStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class DeviceStatusReportServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:01:00Z");
    private static final Instant REPORTED_AT = Instant.parse("2026-08-25T23:59:00Z");

    @Autowired
    private DeviceStatusRepository repository;

    private DeviceStatusReportService service;

    @BeforeEach
    void setUp() {
        service = new DeviceStatusReportService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(DeviceOperationalStatus.class)
    void storesEveryOperationalStatusWithDerivedLegacyFields(DeviceOperationalStatus operationalStatus) {
        DeviceStatusHandlingOutcome outcome = service.record(
                message("pi-001", operationalStatus, "firmware-v1"),
                "pi-001"
        );

        DeviceStatus stored = repository.findTopByDeviceIdOrderByReceivedAtDescIdDesc("pi-001")
                .orElseThrow();
        assertThat(outcome).isEqualTo(DeviceStatusHandlingOutcome.STORED);
        assertThat(stored.getOperationalStatus()).isEqualTo(operationalStatus);
        assertThat(stored.getFirmwareVersion()).isEqualTo("firmware-v1");
        assertThat(stored.getReportedAt()).isEqualTo(REPORTED_AT);
        assertThat(stored.getReceivedAt()).isEqualTo(NOW);
        assertThat(stored.isConnected()).isEqualTo(operationalStatus != DeviceOperationalStatus.OFFLINE);
        assertThat(stored.getLastSeen()).isEqualTo(NOW);
    }

    @Test
    void updatesOnlyLatestExistingRowWhenDeviceIdIsNotUnique() {
        DeviceStatus older = repository.save(new DeviceStatus(
                "pi-duplicate",
                DeviceOperationalStatus.ONLINE,
                "old-v1",
                REPORTED_AT.minusSeconds(20),
                NOW.minusSeconds(20)
        ));
        DeviceStatus latest = repository.saveAndFlush(new DeviceStatus(
                "pi-duplicate",
                DeviceOperationalStatus.DEGRADED,
                "old-v2",
                REPORTED_AT.minusSeconds(10),
                NOW.minusSeconds(10)
        ));

        service.record(
                message("pi-duplicate", DeviceOperationalStatus.OFFLINE, "new-v3"),
                "pi-duplicate"
        );
        repository.flush();

        DeviceStatus unchanged = repository.findById(older.getId()).orElseThrow();
        DeviceStatus updated = repository.findById(latest.getId()).orElseThrow();
        assertThat(unchanged.getFirmwareVersion()).isEqualTo("old-v1");
        assertThat(updated.getFirmwareVersion()).isEqualTo("new-v3");
        assertThat(updated.getOperationalStatus()).isEqualTo(DeviceOperationalStatus.OFFLINE);
        assertThat(updated.isConnected()).isFalse();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void rejectsTopicPayloadMismatchWithoutSaving() {
        assertThat(service.record(
                message("pi-001", DeviceOperationalStatus.ONLINE, "firmware-v1"),
                "pi-other"
        )).isEqualTo(DeviceStatusHandlingOutcome.TOPIC_PAYLOAD_DEVICE_MISMATCH);
        assertThat(repository.count()).isZero();
    }

    private MqttDeviceStatusMessage message(
            String deviceId,
            DeviceOperationalStatus status,
            String firmwareVersion
    ) {
        return new MqttDeviceStatusMessage(deviceId, status, REPORTED_AT, firmwareVersion);
    }
}
