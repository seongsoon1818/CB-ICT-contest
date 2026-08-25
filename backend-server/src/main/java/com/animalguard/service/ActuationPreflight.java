package com.animalguard.service;

import com.animalguard.domain.ActuationBlocker;

import java.util.List;
import java.util.Objects;

public record ActuationPreflight(
        boolean enabled,
        boolean ready,
        List<ActuationBlocker> blockers
) {
    public ActuationPreflight {
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
        if (ready != blockers.isEmpty()) {
            throw new IllegalArgumentException("ready must match whether blockers are empty");
        }
        if (enabled == blockers.contains(ActuationBlocker.ACTUATION_DISABLED)) {
            throw new IllegalArgumentException(
                    "enabled must match absence of ACTUATION_DISABLED blocker"
            );
        }
    }
}
