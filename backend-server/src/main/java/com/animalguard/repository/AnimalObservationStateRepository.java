package com.animalguard.repository;

import com.animalguard.domain.AnimalObservationState;
import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.domain.DeviceCommandStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnimalObservationStateRepository extends JpaRepository<AnimalObservationState, Long> {

    Optional<AnimalObservationState> findByCameraId(String cameraId);

    @Query("""
            SELECT DISTINCT state.id
            FROM AnimalObservationState state, DeviceCommand command
            WHERE command.status IN :terminalStatuses
              AND (
                    command.commandId = state.soundAlertCommandId
                    OR command.commandId = state.deterrentFullCommandId
              )
            ORDER BY state.id ASC
            """)
    List<Long> findTerminalMarkerCandidateIds(
            @Param("terminalStatuses") Collection<DeviceCommandStatus> terminalStatuses
    );

    @Query("""
            SELECT state.id
            FROM AnimalObservationState state
            WHERE state.presenceState = :presenceState
              AND state.updatedAt <= :cutoff
            ORDER BY state.updatedAt ASC, state.id ASC
            """)
    List<Long> findNoEventTimeoutCandidateIds(
            @Param("presenceState") AnimalPresenceState presenceState,
            @Param("cutoff") Instant cutoff
    );

    @Query("""
            SELECT state.id
            FROM AnimalObservationState state, DeviceCommand command
            WHERE state.presenceState = :presenceState
              AND state.deterrentFullCommandId = command.commandId
              AND state.absenceStartedAt IS NULL
              AND state.lastProcessedCapturedAt = state.lastDetectedAt
              AND command.status = :commandStatus
              AND command.executedAt <= :cutoff
            ORDER BY command.executedAt ASC, state.id ASC
            """)
    List<Long> findDeterrentRepeatCandidateIds(
            @Param("presenceState") AnimalPresenceState presenceState,
            @Param("commandStatus") DeviceCommandStatus commandStatus,
            @Param("cutoff") Instant cutoff
    );
}
