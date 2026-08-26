package com.animalguard.repository;

import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {

    Optional<DeviceCommand> findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
            String deviceId,
            DeviceCommandSource source
    );
}
