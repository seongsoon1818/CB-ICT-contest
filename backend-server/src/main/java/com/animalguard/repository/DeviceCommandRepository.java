package com.animalguard.repository;

import com.animalguard.domain.DeviceCommand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {
}
