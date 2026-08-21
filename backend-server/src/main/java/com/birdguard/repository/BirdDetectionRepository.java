package com.birdguard.repository;

import com.birdguard.domain.BirdDetection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BirdDetectionRepository extends JpaRepository<BirdDetection, Long> {
}
