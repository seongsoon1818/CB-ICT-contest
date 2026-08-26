package com.animalguard.service;

import com.animalguard.config.ReconciliationProperties;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.DeviceCommandRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandReconciliationCoordinatorTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-26T00:00:00Z");
    private static final Instant NOW = ISSUED_AT.plusSeconds(20);

    private final DeviceCommandRepository repository = mock(DeviceCommandRepository.class);
    private final CommandReconciliationCoordinator coordinator = new CommandReconciliationCoordinator(
            repository,
            new ReconciliationProperties(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(15),
                    3
            ),
            new DirectTransactionOperations()
    );

    @Test
    void expiresCreatedAtExpiryBoundaryWithoutPublishing() {
        DeviceCommand command = command("command-created", ISSUED_AT.plusSeconds(20));
        when(repository.findByCommandId(command.getCommandId())).thenReturn(Optional.of(command));

        assertThat(coordinator.expireCreated(command.getCommandId(), NOW)).isTrue();

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.EXPIRED);
        assertThat(command.getExpiredAt()).isEqualTo(NOW);
        assertThat(command.getExpiredReportedAt()).isNull();
        assertThat(command.getPublishedAt()).isNull();
    }

    @Test
    void failsPublishedAtTimeoutBoundaryWithoutRepublish() {
        DeviceCommand command = command("command-published", ISSUED_AT.plusSeconds(60));
        command.markPublished(ISSUED_AT.plusSeconds(5));
        when(repository.findByCommandId(command.getCommandId())).thenReturn(Optional.of(command));

        assertThat(coordinator.failPublishedTimeout(command.getCommandId(), NOW)).isTrue();

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(command.getFailedAt()).isEqualTo(NOW);
        assertThat(command.getFailedReportedAt()).isNull();
    }

    @Test
    void failsAcknowledgedAtTimeoutBoundary() {
        DeviceCommand command = command("command-acknowledged", ISSUED_AT.plusSeconds(60));
        command.markPublished(ISSUED_AT.plusSeconds(1));
        command.markAcknowledged(ISSUED_AT.plusSeconds(5), ISSUED_AT.minusSeconds(30));
        when(repository.findByCommandId(command.getCommandId())).thenReturn(Optional.of(command));

        assertThat(coordinator.failAcknowledgedTimeout(command.getCommandId(), NOW)).isTrue();

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(command.getFailedAt()).isEqualTo(NOW);
        assertThat(command.getFailedReportedAt()).isNull();
    }

    @Test
    void reloadsAndLeavesNonMatchingOrNotYetTimedOutStateUnchanged() {
        DeviceCommand created = command("command-not-expired", NOW.plusNanos(1));
        DeviceCommand published = command("command-not-timed-out", ISSUED_AT.plusSeconds(60));
        published.markPublished(ISSUED_AT.plusSeconds(6));
        when(repository.findByCommandId(created.getCommandId())).thenReturn(Optional.of(created));
        when(repository.findByCommandId(published.getCommandId())).thenReturn(Optional.of(published));

        assertThat(coordinator.expireCreated(created.getCommandId(), NOW)).isFalse();
        assertThat(coordinator.failPublishedTimeout(published.getCommandId(), NOW)).isFalse();

        assertThat(created.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
        assertThat(published.getStatus()).isEqualTo(DeviceCommandStatus.PUBLISHED);
    }

    private DeviceCommand command(String commandId, Instant expiresAt) {
        return new DeviceCommand(
                commandId,
                new DetectionEvent(
                        UUID.randomUUID().toString(),
                        "cam-001",
                        ISSUED_AT,
                        1280,
                        720,
                        "animal-detector-v1",
                        null
                ),
                "pi-001",
                DeviceCommandSource.AUTOMATIC,
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "RECONCILIATION_TEST",
                ISSUED_AT,
                expiresAt
        );
    }

    private static final class DirectTransactionOperations implements TransactionOperations {

        private final TransactionStatus status = mock(TransactionStatus.class);

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            return action.doInTransaction(status);
        }
    }
}
