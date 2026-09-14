package com.animalguard.repository;

import com.animalguard.domain.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceStatusRepository extends JpaRepository<DeviceStatus, Long> {

    Optional<DeviceStatus> findTopByDeviceIdOrderByReceivedAtDescIdDesc(String deviceId);
}
