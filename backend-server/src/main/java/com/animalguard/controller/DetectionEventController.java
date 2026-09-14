package com.animalguard.controller;

import com.animalguard.dto.DetectionEventRequest;
import com.animalguard.dto.DetectionEventResponse;
import com.animalguard.service.DetectionEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/detection/events")
@RequiredArgsConstructor
public class DetectionEventController {

    private final DetectionEventService detectionEventService;

    @PostMapping
    public ResponseEntity<DetectionEventResponse> receive(@Valid @RequestBody DetectionEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(detectionEventService.receive(request));
    }
}
