package com.animalguard.repository;

import com.animalguard.domain.DetectionEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DetectionEventRepository extends JpaRepository<DetectionEvent, Long> {

    boolean existsByEventId(String eventId);

    @Query("""
            SELECT event
            FROM DetectionEvent event
            WHERE event.cameraId = :cameraId
              AND event.capturedAt = :capturedAt
              AND EXISTS (
                    SELECT detection.id
                    FROM AnimalDetection detection
                    WHERE detection.event = event
              )
            ORDER BY event.id DESC
            """)
    List<DetectionEvent> findLatestPositiveAt(
            @Param("cameraId") String cameraId,
            @Param("capturedAt") Instant capturedAt,
            Pageable pageable
    );
}
