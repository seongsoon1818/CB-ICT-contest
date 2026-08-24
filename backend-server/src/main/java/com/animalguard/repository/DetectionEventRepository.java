package com.animalguard.repository;

import com.animalguard.domain.DetectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DetectionEventRepository extends JpaRepository<DetectionEvent, Long> {

    boolean existsByEventId(String eventId);
}
