package com.birdguard.repository;

import com.birdguard.domain.DeviceCommand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {
}
