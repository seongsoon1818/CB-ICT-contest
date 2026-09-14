package com.animalguard.service;

import com.animalguard.config.ObservationProperties;
import com.animalguard.config.ReconciliationProperties;
import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.repository.AnimalObservationStateRepository;
import com.animalguard.repository.DeviceCommandRepository;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandReconciliationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:30Z");

    private final DeviceCommandRepository commandRepository = mock(DeviceCommandRepository.class);
    private final AnimalObservationStateRepository observationRepository =
            mock(AnimalObservationStateRepository.class);
    private final CommandReconciliationCoordinator commandCoordinator = mock(CommandReconciliationCoordinator.class);
    private final AnimalObservationService observationService = mock(AnimalObservationService.class);

    @Test
    void runsCommandMarkersNoEventAndRepeatInSafetyOrder() {
        when(commandRepository.findCreatedExpiryCandidateIds(DeviceCommandStatus.CREATED, NOW))
                .thenReturn(List.of("created"));
        when(commandRepository.findPublishedTimeoutCandidateIds(
                DeviceCommandStatus.PUBLISHED,
                NOW.minusSeconds(15)
        )).thenReturn(List.of("published"));
        when(commandRepository.findAcknowledgedTimeoutCandidateIds(
                DeviceCommandStatus.ACKNOWLEDGED,
                NOW.minusSeconds(15)
        )).thenReturn(List.of("acknowledged"));
        when(observationRepository.findTerminalMarkerCandidateIds(anyCollection()))
                .thenReturn(List.of(1L));
        when(observationRepository.findNoEventTimeoutCandidateIds(
                AnimalPresenceState.PRESENT,
                NOW.minusSeconds(10)
        )).thenReturn(List.of(2L));
        when(observationRepository.findDeterrentRepeatCandidateIds(
                AnimalPresenceState.PRESENT,
                DeviceCommandStatus.EXECUTED,
                NOW.minusSeconds(5)
        )).thenReturn(List.of(3L));

        scheduler(Duration.ofSeconds(5)).reconcile();

        org.mockito.InOrder order = inOrder(
                commandRepository,
                commandCoordinator,
                observationRepository,
                observationService
        );
        order.verify(commandRepository).findCreatedExpiryCandidateIds(DeviceCommandStatus.CREATED, NOW);
        order.verify(commandCoordinator).expireCreated("created", NOW);
        order.verify(commandRepository).findPublishedTimeoutCandidateIds(
                DeviceCommandStatus.PUBLISHED,
                NOW.minusSeconds(15)
        );
        order.verify(commandCoordinator).failPublishedTimeout("published", NOW);
        order.verify(commandRepository).findAcknowledgedTimeoutCandidateIds(
                DeviceCommandStatus.ACKNOWLEDGED,
                NOW.minusSeconds(15)
        );
        order.verify(commandCoordinator).failAcknowledgedTimeout("acknowledged", NOW);
        order.verify(observationRepository).findTerminalMarkerCandidateIds(anyCollection());
        order.verify(observationService).reconcileTerminalCommandMarkers(1L, NOW);
        order.verify(observationRepository).findNoEventTimeoutCandidateIds(
                AnimalPresenceState.PRESENT,
                NOW.minusSeconds(10)
        );
        order.verify(observationService).handleNoEventTimeout(2L, NOW);
        order.verify(observationRepository).findDeterrentRepeatCandidateIds(
                AnimalPresenceState.PRESENT,
                DeviceCommandStatus.EXECUTED,
                NOW.minusSeconds(5)
        );
        order.verify(observationService).releaseExecutedDeterrentForRepeat(3L, NOW);
    }

    @Test
    void optimisticConflictIsAttemptedOnceAndDoesNotBlockNextCandidate() {
        emptyOtherStages();
        when(commandRepository.findPublishedTimeoutCandidateIds(
                DeviceCommandStatus.PUBLISHED,
                NOW.minusSeconds(15)
        )).thenReturn(List.of("conflict", "next"));
        when(commandCoordinator.failPublishedTimeout("conflict", NOW))
                .thenThrow(new ObjectOptimisticLockingFailureException("DeviceCommand", "conflict"));

        scheduler(Duration.ZERO).reconcile();

        verify(commandCoordinator, times(1)).failPublishedTimeout("conflict", NOW);
        verify(commandCoordinator, times(1)).failPublishedTimeout("next", NOW);
        verify(observationRepository, times(0)).findDeterrentRepeatCandidateIds(
                AnimalPresenceState.PRESENT,
                DeviceCommandStatus.EXECUTED,
                NOW
        );
    }

    @Test
    void exposesOnlyOneScheduledEntryPoint() {
        assertThat(List.of(CommandReconciliationScheduler.class.getDeclaredMethods()).stream()
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .map(Method::getName))
                .containsExactly("reconcile");
    }

    private void emptyOtherStages() {
        when(commandRepository.findCreatedExpiryCandidateIds(DeviceCommandStatus.CREATED, NOW))
                .thenReturn(List.of());
        when(commandRepository.findAcknowledgedTimeoutCandidateIds(
                DeviceCommandStatus.ACKNOWLEDGED,
                NOW.minusSeconds(15)
        )).thenReturn(List.of());
        when(observationRepository.findTerminalMarkerCandidateIds(anyCollection()))
                .thenReturn(List.of());
        when(observationRepository.findNoEventTimeoutCandidateIds(
                AnimalPresenceState.PRESENT,
                NOW.minusSeconds(10)
        )).thenReturn(List.of());
    }

    private CommandReconciliationScheduler scheduler(Duration repeatInterval) {
        return new CommandReconciliationScheduler(
                commandRepository,
                observationRepository,
                commandCoordinator,
                observationService,
                new ReconciliationProperties(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(15),
                        3
                ),
                new ObservationProperties(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        repeatInterval
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
