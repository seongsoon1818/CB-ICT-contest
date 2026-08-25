package com.animalguard.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceCommandTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-25T03:00:00Z");
    private static final String REASON = "CLASS_SCORE_MAGPIE +30, DETECTION_COUNT_GE_3 +20";

    @Test
    void createsCommandWithRequiredDeliveryFieldsAndCreatedStatus() {
        DeviceCommand command = command();

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
        assertThat(command.getVersion()).isZero();
        assertThat(command.getReason()).isEqualTo(REASON);
        assertThat(command.getIssuedAt()).isEqualTo(ISSUED_AT);
        assertThat(command.getExpiresAt()).isEqualTo(ISSUED_AT.plusSeconds(10));
        assertThat(command.getCreatedAt()).isEqualTo(ISSUED_AT);
        assertThat(command.getPublishedAt()).isNull();
        assertThat(command.getAcknowledgedAt()).isNull();
        assertThat(command.getAcknowledgedReportedAt()).isNull();
        assertThat(command.getExecutedAt()).isNull();
        assertThat(command.getExecutedReportedAt()).isNull();
        assertThat(command.getFailedAt()).isNull();
        assertThat(command.getFailedReportedAt()).isNull();
        assertThat(command.getExpiredAt()).isNull();
        assertThat(command.getExpiredReportedAt()).isNull();
    }

    @Test
    void rejectsInvalidCreationInputBeforeConstructingCommand() {
        assertThatThrownBy(() -> command(0, REASON, ISSUED_AT, ISSUED_AT.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMs");
        assertThatThrownBy(() -> command(5_000, " ", ISSUED_AT, ISSUED_AT.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> command(5_000, REASON, null, ISSUED_AT.plusSeconds(10)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("issuedAt");
        assertThatThrownBy(() -> command(5_000, REASON, ISSUED_AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("expiresAt");
        assertThatThrownBy(() -> command(5_000, REASON, ISSUED_AT, ISSUED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");
    }

    @Test
    void followsPublishedAcknowledgedExecutedPath() {
        DeviceCommand command = command();
        Instant publishedAt = ISSUED_AT.plusSeconds(1);
        Instant acknowledgedAt = ISSUED_AT.plusSeconds(2);
        Instant executedAt = ISSUED_AT.plusSeconds(3);
        Instant acknowledgedReportedAt = ISSUED_AT.minusSeconds(30);
        Instant executedReportedAt = ISSUED_AT.minusSeconds(29);

        command.markPublished(publishedAt);
        command.markAcknowledged(acknowledgedAt, acknowledgedReportedAt);
        command.markExecuted(executedAt, executedReportedAt);

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.EXECUTED);
        assertThat(command.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(command.getAcknowledgedAt()).isEqualTo(acknowledgedAt);
        assertThat(command.getAcknowledgedReportedAt()).isEqualTo(acknowledgedReportedAt);
        assertThat(command.getExecutedAt()).isEqualTo(executedAt);
        assertThat(command.getExecutedReportedAt()).isEqualTo(executedReportedAt);
    }

    @Test
    void allowsFailedFromPublishedAndAcknowledged() {
        DeviceCommand published = command();
        published.markPublished(ISSUED_AT.plusSeconds(1));
        published.markFailed(ISSUED_AT.plusSeconds(2), null);

        DeviceCommand acknowledged = command();
        acknowledged.markPublished(ISSUED_AT.plusSeconds(1));
        acknowledged.markAcknowledged(ISSUED_AT.plusSeconds(2), ISSUED_AT.minusSeconds(30));
        acknowledged.markFailed(ISSUED_AT.plusSeconds(3), ISSUED_AT.minusSeconds(29));

        assertThat(published.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(published.getFailedAt()).isEqualTo(ISSUED_AT.plusSeconds(2));
        assertThat(published.getFailedReportedAt()).isNull();
        assertThat(acknowledged.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(acknowledged.getFailedAt()).isEqualTo(ISSUED_AT.plusSeconds(3));
        assertThat(acknowledged.getFailedReportedAt()).isEqualTo(ISSUED_AT.minusSeconds(29));
    }

    @Test
    void allowsExpiredFromCreatedAndPublished() {
        DeviceCommand created = command();
        created.markExpired(ISSUED_AT.plusSeconds(1), null);

        DeviceCommand published = command();
        published.markPublished(ISSUED_AT.plusSeconds(1));
        published.markExpired(ISSUED_AT.plusSeconds(2), ISSUED_AT.minusSeconds(30));

        assertThat(created.getStatus()).isEqualTo(DeviceCommandStatus.EXPIRED);
        assertThat(created.getExpiredAt()).isEqualTo(ISSUED_AT.plusSeconds(1));
        assertThat(created.getExpiredReportedAt()).isNull();
        assertThat(published.getStatus()).isEqualTo(DeviceCommandStatus.EXPIRED);
        assertThat(published.getExpiredAt()).isEqualTo(ISSUED_AT.plusSeconds(2));
        assertThat(published.getExpiredReportedAt()).isEqualTo(ISSUED_AT.minusSeconds(30));
    }

    @Test
    void treatsSameStatusApplicationsAsTimestampPreservingNoOps() {
        DeviceCommand executed = command();
        executed.markPublished(ISSUED_AT.plusSeconds(1));
        executed.markPublished(ISSUED_AT.plusSeconds(9));
        executed.markAcknowledged(ISSUED_AT.plusSeconds(2), ISSUED_AT.minusSeconds(30));
        executed.markAcknowledged(ISSUED_AT.plusSeconds(9), ISSUED_AT.plusSeconds(9));
        executed.markExecuted(ISSUED_AT.plusSeconds(3), ISSUED_AT.minusSeconds(29));
        executed.markExecuted(ISSUED_AT.plusSeconds(9), ISSUED_AT.plusSeconds(9));

        DeviceCommand failed = command();
        failed.markPublished(ISSUED_AT.plusSeconds(1));
        failed.markFailed(ISSUED_AT.plusSeconds(2), null);
        failed.markFailed(ISSUED_AT.plusSeconds(9), ISSUED_AT.plusSeconds(9));

        DeviceCommand expired = command();
        expired.markExpired(ISSUED_AT.plusSeconds(1), null);
        expired.markExpired(ISSUED_AT.plusSeconds(9), ISSUED_AT.plusSeconds(9));

        assertThat(executed.getPublishedAt()).isEqualTo(ISSUED_AT.plusSeconds(1));
        assertThat(executed.getAcknowledgedAt()).isEqualTo(ISSUED_AT.plusSeconds(2));
        assertThat(executed.getAcknowledgedReportedAt()).isEqualTo(ISSUED_AT.minusSeconds(30));
        assertThat(executed.getExecutedAt()).isEqualTo(ISSUED_AT.plusSeconds(3));
        assertThat(executed.getExecutedReportedAt()).isEqualTo(ISSUED_AT.minusSeconds(29));
        assertThat(failed.getFailedAt()).isEqualTo(ISSUED_AT.plusSeconds(2));
        assertThat(failed.getFailedReportedAt()).isNull();
        assertThat(expired.getExpiredAt()).isEqualTo(ISSUED_AT.plusSeconds(1));
        assertThat(expired.getExpiredReportedAt()).isNull();
    }

    @Test
    void rejectsSkippedAndReverseTransitions() {
        DeviceCommand created = command();
        assertThatThrownBy(() -> created.markAcknowledged(ISSUED_AT.plusSeconds(1), ISSUED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> created.markExecuted(ISSUED_AT.plusSeconds(1), ISSUED_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> created.markFailed(ISSUED_AT.plusSeconds(1), null))
                .isInstanceOf(IllegalStateException.class);

        DeviceCommand acknowledged = command();
        acknowledged.markPublished(ISSUED_AT.plusSeconds(1));
        acknowledged.markAcknowledged(ISSUED_AT.plusSeconds(2), ISSUED_AT);
        assertThatThrownBy(() -> acknowledged.markPublished(ISSUED_AT.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsChangesAfterTerminalStatus() {
        DeviceCommand executed = command();
        executed.markPublished(ISSUED_AT.plusSeconds(1));
        executed.markAcknowledged(ISSUED_AT.plusSeconds(2), ISSUED_AT);
        executed.markExecuted(ISSUED_AT.plusSeconds(3), ISSUED_AT.plusSeconds(1));

        DeviceCommand failed = command();
        failed.markPublished(ISSUED_AT.plusSeconds(1));
        failed.markFailed(ISSUED_AT.plusSeconds(2), null);

        DeviceCommand expired = command();
        expired.markExpired(ISSUED_AT.plusSeconds(1), null);

        assertThatThrownBy(() -> executed.markFailed(ISSUED_AT.plusSeconds(4), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.markExpired(ISSUED_AT.plusSeconds(3), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> expired.markPublished(ISSUED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPastTransitionTimestampsWithoutPartialMutation() {
        DeviceCommand command = command();

        assertThatThrownBy(() -> command.markPublished(ISSUED_AT.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
        assertThat(command.getPublishedAt()).isNull();

        command.markPublished(ISSUED_AT.plusSeconds(2));
        assertThatThrownBy(() -> command.markAcknowledged(
                ISSUED_AT.plusSeconds(1),
                ISSUED_AT.minusSeconds(30)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.PUBLISHED);
        assertThat(command.getAcknowledgedAt()).isNull();

        command.markAcknowledged(ISSUED_AT.plusSeconds(3), ISSUED_AT.minusSeconds(30));
        assertThatThrownBy(() -> command.markExecuted(
                ISSUED_AT.plusSeconds(2),
                ISSUED_AT.minusSeconds(29)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
        assertThatThrownBy(() -> command.markFailed(ISSUED_AT.plusSeconds(2), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.ACKNOWLEDGED);
        assertThat(command.getExecutedAt()).isNull();
        assertThat(command.getFailedAt()).isNull();
    }

    @Test
    void requiresExpiredTimestampNotBeforeLatestDeliveryTimestamp() {
        DeviceCommand created = command();
        assertThatThrownBy(() -> created.markExpired(ISSUED_AT.minusNanos(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(created.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);

        DeviceCommand published = command();
        published.markPublished(ISSUED_AT.plusSeconds(2));
        assertThatThrownBy(() -> published.markExpired(ISSUED_AT.plusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(published.getStatus()).isEqualTo(DeviceCommandStatus.PUBLISHED);
        assertThat(published.getExpiredAt()).isNull();
    }

    @Test
    void requiresDeviceReportedTimestampsForAcknowledgedAndExecuted() {
        DeviceCommand command = command();
        command.markPublished(ISSUED_AT.plusSeconds(1));

        assertThatThrownBy(() -> command.markAcknowledged(ISSUED_AT.plusSeconds(2), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reported");

        command.markAcknowledged(ISSUED_AT.plusSeconds(2), ISSUED_AT.minusSeconds(30));
        assertThatThrownBy(() -> command.markExecuted(ISSUED_AT.plusSeconds(3), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reported");
    }

    private DeviceCommand command() {
        return command(5_000, REASON, ISSUED_AT, ISSUED_AT.plusSeconds(10));
    }

    private DeviceCommand command(int durationMs, String reason, Instant issuedAt, Instant expiresAt) {
        return new DeviceCommand(
                "command-001",
                event(),
                "pi-001",
                "DETERRENT_LEVEL_2",
                durationMs,
                reason,
                issuedAt,
                expiresAt
        );
    }

    private DetectionEvent event() {
        return new DetectionEvent(
                "event-001",
                "cam-001",
                ISSUED_AT,
                1280,
                720,
                "animal-detector-v1",
                null
        );
    }
}
