package com.animalguard.repository;

import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, Long> {

    Optional<DeviceCommand> findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
            String deviceId,
            DeviceCommandSource source
    );

    Optional<DeviceCommand> findByCommandId(String commandId);

    @Query("""
            SELECT command.commandId
            FROM DeviceCommand command
            WHERE command.status = :status
            ORDER BY command.createdAt ASC, command.id ASC
            """)
    List<String> findDispatchCandidateCommandIds(
            @Param("status") DeviceCommandStatus status,
            Pageable pageable
    );

    @Query("""
            SELECT COUNT(command)
            FROM DeviceCommand command
            JOIN command.event event
            WHERE event.cameraId = :cameraId
              AND command.source = :source
              AND command.commandType = :commandType
              AND event.capturedAt >= :sessionFirstDetectedAt
            """)
    long countAutomaticAttemptsInObservationSession(
            @Param("cameraId") String cameraId,
            @Param("source") DeviceCommandSource source,
            @Param("commandType") com.animalguard.domain.DeviceCommandType commandType,
            @Param("sessionFirstDetectedAt") Instant sessionFirstDetectedAt
    );

    @Query("""
            SELECT command.commandId
            FROM DeviceCommand command
            WHERE command.status = :status
              AND command.expiresAt <= :now
            ORDER BY command.expiresAt ASC, command.id ASC
            """)
    List<String> findCreatedExpiryCandidateIds(
            @Param("status") DeviceCommandStatus status,
            @Param("now") Instant now
    );

    @Query("""
            SELECT command.commandId
            FROM DeviceCommand command
            WHERE command.status = :status
              AND command.publishedAt <= :cutoff
            ORDER BY command.publishedAt ASC, command.id ASC
            """)
    List<String> findPublishedTimeoutCandidateIds(
            @Param("status") DeviceCommandStatus status,
            @Param("cutoff") Instant cutoff
    );

    @Query("""
            SELECT command.commandId
            FROM DeviceCommand command
            WHERE command.status = :status
              AND command.acknowledgedAt <= :cutoff
            ORDER BY command.acknowledgedAt ASC, command.id ASC
            """)
    List<String> findAcknowledgedTimeoutCandidateIds(
            @Param("status") DeviceCommandStatus status,
            @Param("cutoff") Instant cutoff
    );
}
