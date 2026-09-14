package com.animalguard.service;

import com.animalguard.config.ObservationProperties;
import com.animalguard.config.ReconciliationProperties;
import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.repository.AnimalObservationStateRepository;
import com.animalguard.repository.DeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class CommandReconciliationScheduler {

    private static final EnumSet<DeviceCommandStatus> CLEARABLE_TERMINAL_STATUSES = EnumSet.of(
            DeviceCommandStatus.FAILED,
            DeviceCommandStatus.EXPIRED
    );

    private final DeviceCommandRepository commandRepository;
    private final AnimalObservationStateRepository observationRepository;
    private final CommandReconciliationCoordinator commandCoordinator;
    private final AnimalObservationService observationService;
    private final ReconciliationProperties reconciliationProperties;
    private final ObservationProperties observationProperties;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${animalguard.reconciliation.scan-interval:1s}",
            initialDelayString = "${animalguard.reconciliation.scan-interval:1s}"
    )
    public void reconcile() {
        Instant now = clock.instant();
        expireCreated(now);
        failPublished(now);
        failAcknowledged(now);
        reconcileTerminalMarkers(now);
        handleNoEventTimeouts(now);
        releaseRepeatDeterrents(now);
    }

    private void expireCreated(Instant now) {
        candidates("CREATED expiry", () -> commandRepository.findCreatedExpiryCandidateIds(
                DeviceCommandStatus.CREATED,
                now
        )).forEach(commandId -> runCandidate(
                "CREATED expiry",
                commandId,
                () -> commandCoordinator.expireCreated(commandId, now)
        ));
    }

    private void failPublished(Instant now) {
        Instant cutoff = now.minus(reconciliationProperties.publishedTimeout());
        candidates("PUBLISHED timeout", () -> commandRepository.findPublishedTimeoutCandidateIds(
                DeviceCommandStatus.PUBLISHED,
                cutoff
        )).forEach(commandId -> runCandidate(
                "PUBLISHED timeout",
                commandId,
                () -> commandCoordinator.failPublishedTimeout(commandId, now)
        ));
    }

    private void failAcknowledged(Instant now) {
        Instant cutoff = now.minus(reconciliationProperties.acknowledgedTimeout());
        candidates("ACKNOWLEDGED timeout", () -> commandRepository.findAcknowledgedTimeoutCandidateIds(
                DeviceCommandStatus.ACKNOWLEDGED,
                cutoff
        )).forEach(commandId -> runCandidate(
                "ACKNOWLEDGED timeout",
                commandId,
                () -> commandCoordinator.failAcknowledgedTimeout(commandId, now)
        ));
    }

    private void reconcileTerminalMarkers(Instant now) {
        candidates("terminal marker", () -> observationRepository.findTerminalMarkerCandidateIds(
                CLEARABLE_TERMINAL_STATUSES
        )).forEach(observationId -> runCandidate(
                "terminal marker",
                observationId,
                () -> observationService.reconcileTerminalCommandMarkers(observationId, now)
        ));
    }

    private void handleNoEventTimeouts(Instant now) {
        Instant cutoff = now.minus(observationProperties.noEventTimeout());
        candidates("no-event watchdog", () -> observationRepository.findNoEventTimeoutCandidateIds(
                AnimalPresenceState.PRESENT,
                cutoff
        )).forEach(observationId -> runCandidate(
                "no-event watchdog",
                observationId,
                () -> observationService.handleNoEventTimeout(observationId, now)
        ));
    }

    private void releaseRepeatDeterrents(Instant now) {
        if (observationProperties.deterrentRepeatInterval().isZero()) {
            return;
        }
        Instant cutoff = now.minus(observationProperties.deterrentRepeatInterval());
        candidates("deterrent repeat", () -> observationRepository.findDeterrentRepeatCandidateIds(
                AnimalPresenceState.PRESENT,
                DeviceCommandStatus.EXECUTED,
                cutoff
        )).forEach(observationId -> runCandidate(
                "deterrent repeat",
                observationId,
                () -> observationService.releaseExecutedDeterrentForRepeat(observationId, now)
        ));
    }

    private <T> List<T> candidates(String phase, Supplier<List<T>> query) {
        try {
            return query.get();
        } catch (DataAccessException exception) {
            log.error("Reconciliation candidate query failed: phase={}", phase, exception);
        } catch (RuntimeException exception) {
            log.error("Unexpected reconciliation candidate query failure: phase={}", phase, exception);
        }
        return List.of();
    }

    private void runCandidate(String phase, Object candidateId, Supplier<?> action) {
        try {
            action.get();
        } catch (ObjectOptimisticLockingFailureException exception) {
            log.warn(
                    "Reconciliation lost optimistic-lock race without retry: phase={}, candidateId={}",
                    phase,
                    candidateId
            );
        } catch (DataAccessException exception) {
            log.error(
                    "Reconciliation persistence failure without retry: phase={}, candidateId={}",
                    phase,
                    candidateId,
                    exception
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Unexpected reconciliation failure without retry: phase={}, candidateId={}",
                    phase,
                    candidateId,
                    exception
            );
        }
    }
}
