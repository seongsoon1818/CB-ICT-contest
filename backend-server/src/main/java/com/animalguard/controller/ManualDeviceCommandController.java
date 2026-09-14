package com.animalguard.controller;

import com.animalguard.domain.CommandOutcome;
import com.animalguard.dto.ManualDeviceCommandRequest;
import com.animalguard.dto.ManualDeviceCommandResponse;
import com.animalguard.service.CommandDecision;
import com.animalguard.service.ManualDeviceCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class ManualDeviceCommandController {

    private final ManualDeviceCommandService service;

    @PostMapping("/{deviceId}/commands")
    public ResponseEntity<ManualDeviceCommandResponse> create(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-Operator-Token", required = false) String operatorToken,
            @Valid @RequestBody ManualDeviceCommandRequest request
    ) {
        CommandDecision decision = service.create(
                deviceId,
                request.requestId(),
                request.command(),
                operatorToken
        );
        HttpStatus status = decision.outcome() == CommandOutcome.CREATED
                ? HttpStatus.CREATED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(ManualDeviceCommandResponse.from(decision));
    }
}
