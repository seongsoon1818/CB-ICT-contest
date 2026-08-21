package com.birdguard.repository;

import com.birdguard.domain.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceStatusRepository extends JpaRepository<DeviceStatus, Long> {
}
