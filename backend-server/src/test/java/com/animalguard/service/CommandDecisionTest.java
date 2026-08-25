package com.animalguard.service;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.CommandOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandDecisionTest {

    @Test
    void createsNotRequestedDecisionWithNoCommandOrBlockers() {
        CommandDecision decision = CommandDecision.notRequested();

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.NOT_REQUESTED);
        assertThat(decision.commandId()).isNull();
        assertThat(decision.blockers()).isEmpty();
    }

    @Test
    void createsCreatedDecisionWithCommandIdAndNoBlockers() {
        CommandDecision decision = CommandDecision.created("command-001");

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(decision.commandId()).isEqualTo("command-001");
        assertThat(decision.blockers()).isEmpty();
    }

    @Test
    void createsSuppressedDecisionWithBlockersAndNoCommand() {
        CommandDecision decision = CommandDecision.suppressed(List.of(ActuationBlocker.CAMERA_UNMAPPED));

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.commandId()).isNull();
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.CAMERA_UNMAPPED);
    }

    @Test
    void rejectsInvalidNotRequestedCombination() {
        assertThatThrownBy(() -> new CommandDecision(
                CommandOutcome.NOT_REQUESTED,
                "command-001",
                List.of()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CommandDecision(
                CommandOutcome.NOT_REQUESTED,
                null,
                List.of(ActuationBlocker.CAMERA_UNMAPPED)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCreatedCombination() {
        assertThatThrownBy(() -> CommandDecision.created(" "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CommandDecision(
                CommandOutcome.CREATED,
                "command-001",
                List.of(ActuationBlocker.COOLDOWN_ACTIVE)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidSuppressedCombination() {
        assertThatThrownBy(() -> CommandDecision.suppressed(List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new CommandDecision(
                CommandOutcome.SUPPRESSED,
                "command-001",
                List.of(ActuationBlocker.CAMERA_UNMAPPED)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullDecisionFieldsAndNullBlocker() {
        assertThatThrownBy(() -> new CommandDecision(null, null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommandDecision(CommandOutcome.SUPPRESSED, null, null))
                .isInstanceOf(NullPointerException.class);

        List<ActuationBlocker> blockersWithNull = new ArrayList<>();
        blockersWithNull.add(null);
        assertThatThrownBy(() -> CommandDecision.suppressed(blockersWithNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void defensivelyCopiesBlockersAndExposesUnmodifiableList() {
        List<ActuationBlocker> source = new ArrayList<>();
        source.add(ActuationBlocker.CAMERA_UNMAPPED);

        CommandDecision decision = CommandDecision.suppressed(source);
        source.add(ActuationBlocker.COOLDOWN_ACTIVE);

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.CAMERA_UNMAPPED);
        assertThatThrownBy(() -> decision.blockers().add(ActuationBlocker.COOLDOWN_ACTIVE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
