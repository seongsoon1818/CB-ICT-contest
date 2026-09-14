package com.animalguard.mqtt;

import com.animalguard.domain.DeviceStatus;
import com.animalguard.repository.DeviceStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DeviceStatusReportService {

    private final DeviceStatusRepository repository;
    private final Clock clock;

    @Transactional
    public DeviceStatusHandlingOutcome record(MqttDeviceStatusMessage message, String topicDeviceId) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(topicDeviceId, "topicDeviceId must not be null");
        if (!topicDeviceId.equals(message.deviceId())) {
            return DeviceStatusHandlingOutcome.TOPIC_PAYLOAD_DEVICE_MISMATCH;
        }

        Instant receivedAt = clock.instant();
        DeviceStatus status = repository.findTopByDeviceIdOrderByReceivedAtDescIdDesc(message.deviceId())
                .orElseGet(() -> new DeviceStatus(
                        message.deviceId(),
                        message.status(),
                        message.firmwareVersion(),
                        message.reportedAt(),
                        receivedAt
                ));
        if (status.getId() != null) {
            status.recordReport(
                    message.status(),
                    message.firmwareVersion(),
                    message.reportedAt(),
                    receivedAt
            );
        }
        repository.save(status);
        return DeviceStatusHandlingOutcome.STORED;
    }
}
