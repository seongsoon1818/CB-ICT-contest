package com.animalguard.repository;

import com.animalguard.domain.AnimalDetection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnimalDetectionRepository extends JpaRepository<AnimalDetection, Long> {
}
