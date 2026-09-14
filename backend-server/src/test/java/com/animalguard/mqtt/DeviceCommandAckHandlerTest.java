package com.animalguard.mqtt;

import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.DeviceCommandRepository;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceCommandAckHandlerTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-26T00:00:00Z");
    private static final Instant NOW = ISSUED_AT.plusSeconds(10);
    private static final Instant REPORTED_AT = ISSUED_AT.minusSeconds(30);

    private final DeviceCommandRepository repository = mock(DeviceCommandRepository.class);

    @Test
    void appliesEveryAllowedAckTransitionWithSeparateBackendAndDeviceTimes() {
        DeviceCommand published = command("command-acknowledged");
        published.markPublished(ISSUED_AT.plusSeconds(1));
        DeviceCommand acknowledged = command("command-executed");
        acknowledged.markPublished(ISSUED_AT.plusSeconds(1));
        acknowledged.markAcknowledged(ISSUED_AT.plusSeconds(2), REPORTED_AT);
        DeviceCommand publishedToFail = command("command-failed-published");
        publishedToFail.markPublished(ISSUED_AT.plusSeconds(1));
        DeviceCommand acknowledgedToFail = command("command-failed-acknowledged");
        acknowledgedToFail.markPublished(ISSUED_AT.plusSeconds(1));
        acknowledgedToFail.markAcknowledged(ISSUED_AT.plusSeconds(2), REPORTED_AT);
        DeviceCommand createdToExpire = command("command-expired-created");
        DeviceCommand publishedToExpire = command("command-expired-published");
        publishedToExpire.markPublished(ISSUED_AT.plusSeconds(1));
        stub(published, acknowledged, publishedToFail, acknowledgedToFail, createdToExpire, publishedToExpire);
        DeviceCommandAckHandler handler = handler(new DirectTransactionOperations());

        assertApplied(handler.handle(message("command-acknowledged", MqttAckStatus.ACKNOWLEDGED), "pi-001"));
        assertApplied(handler.handle(message("command-executed", MqttAckStatus.EXECUTED), "pi-001"));
        assertApplied(handler.handle(message("command-failed-published", MqttAckStatus.FAILED), "pi-001"));
        assertApplied(handler.handle(message("command-failed-acknowledged", MqttAckStatus.FAILED), "pi-001"));
        assertApplied(handler.handle(message("command-expired-created", MqttAckStatus.EXPIRED), "pi-001"));
        assertApplied(handler.handle(message("command-expired-published", MqttAckStatus.EXPIRED), "pi-001"));

        assertThat(published.getStatus()).isEqualTo(DeviceCommandStatus.ACKNOWLEDGED);
        assertThat(published.getAcknowledgedAt()).isEqualTo(NOW);
        assertThat(published.getAcknowledgedReportedAt()).isEqualTo(REPORTED_AT);
        assertThat(acknowledged.getStatus()).isEqualTo(DeviceCommandStatus.EXECUTED);
        assertThat(acknowledged.getExecutedAt()).isEqualTo(NOW);
        assertThat(publishedToFail.getFailedReportedAt()).isEqualTo(REPORTED_AT);
        assertThat(acknowledgedToFail.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(createdToExpire.getExpiredAt()).isEqualTo(NOW);
        assertThat(publishedToExpire.getExpiredReportedAt()).isEqualTo(REPORTED_AT);
    }

    @Test
    void treatsDuplicateAndAdvancedAcksAsIdempotentWithoutChangingStoredTimestamp() {
        DeviceCommand acknowledged = command("command-duplicate");
        acknowledged.markPublished(ISSUED_AT.plusSeconds(1));
        acknowledged.markAcknowledged(ISSUED_AT.plusSeconds(2), REPORTED_AT);
        DeviceCommand executed = command("command-advanced");
        executed.markPublished(ISSUED_AT.plusSeconds(1));
        executed.markAcknowledged(ISSUED_AT.plusSeconds(2), REPORTED_AT);
        executed.markExecuted(ISSUED_AT.plusSeconds(3), REPORTED_AT.plusSeconds(1));
        stub(acknowledged, executed);
        DeviceCommandAckHandler handler = handler(new DirectTransactionOperations());

        AckHandlingResult duplicate = handler.handle(
                new MqttAckMessage("command-duplicate", "pi-001", MqttAckStatus.ACKNOWLEDGED, NOW),
                "pi-001"
        );
        AckHandlingResult advanced = handler.handle(
                message("command-advanced", MqttAckStatus.ACKNOWLEDGED),
                "pi-001"
        );

        assertThat(duplicate.outcome()).isEqualTo(AckHandlingOutcome.IDEMPOTENT);
        assertThat(acknowledged.getAcknowledgedAt()).isEqualTo(ISSUED_AT.plusSeconds(2));
        assertThat(acknowledged.getAcknowledgedReportedAt()).isEqualTo(REPORTED_AT);
        assertThat(advanced.outcome()).isEqualTo(AckHandlingOutcome.ADVANCED_IGNORED);
        assertThat(executed.getStatus()).isEqualTo(DeviceCommandStatus.EXECUTED);
    }

    @Test
    void classifiesOrderViolationsAndTerminalConflictsWithoutMutation() {
        DeviceCommand created = command("command-created");
        DeviceCommand failed = command("command-terminal");
        failed.markPublished(ISSUED_AT.plusSeconds(1));
        failed.markFailed(ISSUED_AT.plusSeconds(2), REPORTED_AT);
        DeviceCommand acknowledged = command("command-expiry-conflict");
        acknowledged.markPublished(ISSUED_AT.plusSeconds(1));
        acknowledged.markAcknowledged(ISSUED_AT.plusSeconds(2), REPORTED_AT);
        stub(created, failed, acknowledged);
        DeviceCommandAckHandler handler = handler(new DirectTransactionOperations());

        assertThat(handler.handle(message("command-created", MqttAckStatus.ACKNOWLEDGED), "pi-001").outcome())
                .isEqualTo(AckHandlingOutcome.ORDER_VIOLATION);
        assertThat(handler.handle(message("command-terminal", MqttAckStatus.EXECUTED), "pi-001").outcome())
                .isEqualTo(AckHandlingOutcome.TERMINAL_CONFLICT);
        assertThat(handler.handle(message("command-expiry-conflict", MqttAckStatus.EXPIRED), "pi-001").outcome())
                .isEqualTo(AckHandlingOutcome.TERMINAL_CONFLICT);
        assertThat(created.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
        assertThat(failed.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(acknowledged.getStatus()).isEqualTo(DeviceCommandStatus.ACKNOWLEDGED);
    }

    @Test
    void rejectsTopicPayloadAndDatabaseDeviceMismatchAndUnknownCommand() {
        DeviceCommand command = command("command-db-mismatch");
        command.markPublished(ISSUED_AT.plusSeconds(1));
        when(repository.findByCommandId("command-db-mismatch")).thenReturn(Optional.of(command));
        when(repository.findByCommandId("command-unknown")).thenReturn(Optional.empty());
        DeviceCommandAckHandler handler = handler(new DirectTransactionOperations());

        AckHandlingResult topicMismatch = handler.handle(
                message("command-db-mismatch", MqttAckStatus.ACKNOWLEDGED),
                "pi-other"
        );
        assertThat(topicMismatch.outcome()).isEqualTo(AckHandlingOutcome.TOPIC_PAYLOAD_DEVICE_MISMATCH);
        verifyNoInteractions(repository);

        AckHandlingResult databaseMismatch = handler.handle(
                new MqttAckMessage(
                        "command-db-mismatch", "pi-other", MqttAckStatus.ACKNOWLEDGED, REPORTED_AT
                ),
                "pi-other"
        );
        AckHandlingResult unknown = handler.handle(
                message("command-unknown", MqttAckStatus.ACKNOWLEDGED),
                "pi-001"
        );

        assertThat(databaseMismatch.outcome()).isEqualTo(AckHandlingOutcome.DATABASE_DEVICE_MISMATCH);
        assertThat(unknown.outcome()).isEqualTo(AckHandlingOutcome.UNKNOWN_COMMAND);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.PUBLISHED);
    }

    @Test
    void reloadsAndRetriesOneClearlyApplicableAckAfterOptimisticConflict() {
        DeviceCommand published = command("command-retry");
        published.markPublished(ISSUED_AT.plusSeconds(1));
        stub(published);
        ConflictThenDirectTransactions transactions = new ConflictThenDirectTransactions(1);

        AckHandlingResult result = handler(transactions).handle(
                message("command-retry", MqttAckStatus.ACKNOWLEDGED),
                "pi-001"
        );

        assertThat(result.outcome()).isEqualTo(AckHandlingOutcome.APPLIED);
        assertThat(result.retriedAfterOptimisticLock()).isTrue();
        assertThat(transactions.executions).isEqualTo(2);
        assertThat(published.getStatus()).isEqualTo(DeviceCommandStatus.ACKNOWLEDGED);
    }

    @Test
    void reloadsAdvancedStateAfterConflictWithoutApplyingAgain() {
        DeviceCommand executed = command("command-reloaded-advanced");
        executed.markPublished(ISSUED_AT.plusSeconds(1));
        executed.markAcknowledged(ISSUED_AT.plusSeconds(2), REPORTED_AT);
        executed.markExecuted(ISSUED_AT.plusSeconds(3), REPORTED_AT.plusSeconds(1));
        stub(executed);

        AckHandlingResult result = handler(new ConflictThenDirectTransactions(1)).handle(
                message("command-reloaded-advanced", MqttAckStatus.ACKNOWLEDGED),
                "pi-001"
        );

        assertThat(result.outcome()).isEqualTo(AckHandlingOutcome.ADVANCED_IGNORED);
        assertThat(result.retriedAfterOptimisticLock()).isTrue();
        assertThat(executed.getStatus()).isEqualTo(DeviceCommandStatus.EXECUTED);
    }

    @Test
    void stopsAfterSecondOptimisticConflict() {
        ConflictThenDirectTransactions transactions = new ConflictThenDirectTransactions(2);

        AckHandlingResult result = handler(transactions).handle(
                message("command-conflict", MqttAckStatus.ACKNOWLEDGED),
                "pi-001"
        );

        assertThat(result.outcome()).isEqualTo(AckHandlingOutcome.OPTIMISTIC_LOCK_ABORTED);
        assertThat(result.retriedAfterOptimisticLock()).isTrue();
        assertThat(transactions.executions).isEqualTo(2);
        verifyNoInteractions(repository);
    }

    private DeviceCommandAckHandler handler(TransactionOperations transactions) {
        return new DeviceCommandAckHandler(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactions
        );
    }

    private void stub(DeviceCommand... commands) {
        for (DeviceCommand command : commands) {
            when(repository.findByCommandId(command.getCommandId())).thenReturn(Optional.of(command));
        }
    }

    private void assertApplied(AckHandlingResult result) {
        assertThat(result.outcome()).isEqualTo(AckHandlingOutcome.APPLIED);
        assertThat(result.retriedAfterOptimisticLock()).isFalse();
    }

    private MqttAckMessage message(String commandId, MqttAckStatus status) {
        return new MqttAckMessage(commandId, "pi-001", status, REPORTED_AT);
    }

    private DeviceCommand command(String commandId) {
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
                "FIRST_ANIMAL_DETECTION",
                ISSUED_AT,
                ISSUED_AT.plusSeconds(60)
        );
    }

    private static class DirectTransactionOperations implements TransactionOperations {

        private final TransactionStatus status = mock(TransactionStatus.class);

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            return action.doInTransaction(status);
        }
    }

    private static final class ConflictThenDirectTransactions extends DirectTransactionOperations {

        private final int conflicts;
        private int executions;

        private ConflictThenDirectTransactions(int conflicts) {
            this.conflicts = conflicts;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            executions++;
            if (executions <= conflicts) {
                throw new ObjectOptimisticLockingFailureException("DeviceCommand", "command-conflict");
            }
            return super.execute(action);
        }
    }
}
