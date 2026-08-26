package com.animalguard.repository;

import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
              AND command.source = :source
            ORDER BY command.createdAt ASC, command.id ASC
            """)
    List<String> findDispatchCandidateCommandIds(
            @Param("status") DeviceCommandStatus status,
            @Param("source") DeviceCommandSource source,
            Pageable pageable
    );
}
