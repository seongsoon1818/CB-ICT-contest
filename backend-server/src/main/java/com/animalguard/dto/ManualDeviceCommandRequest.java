package com.animalguard.dto;

import com.animalguard.domain.DeviceCommandType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ManualDeviceCommandRequest(
        @NotNull UUID requestId,
        @NotNull DeviceCommandType command
) {
}
