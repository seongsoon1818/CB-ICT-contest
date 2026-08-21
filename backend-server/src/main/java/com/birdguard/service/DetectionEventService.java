package com.birdguard.service;

import com.birdguard.domain.BirdDetection;
import com.birdguard.domain.DeviceCommand;
import com.birdguard.domain.DeviceCommandStatus;
import com.birdguard.domain.DetectionEvent;
import com.birdguard.domain.RiskDecision;
import com.birdguard.domain.RiskLevel;
import com.birdguard.dto.DetectionEventRequest;
import com.birdguard.dto.DetectionEventResponse;
import com.birdguard.exception.DuplicateDetectionEventException;
import com.birdguard.repository.DeviceCommandRepository;
import com.birdguard.repository.DetectionEventRepository;
import com.birdguard.repository.RiskDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DetectionEventService {

    private static final String COMMAND_TYPE = "DETERRENT_LEVEL_2";
    private static final int COMMAND_DURATION_MS = 5_000;

    private final DetectionEventRepository detectionEventRepository;
    private final RiskDecisionRepository riskDecisionRepository;
    private final DeviceCommandRepository deviceCommandRepository;
    private final RiskDecisionEngine riskDecisionEngine;

    @Transactional
    public DetectionEventResponse receive(DetectionEventRequest request) {
        if (detectionEventRepository.existsByEventId(request.eventId())) {
            throw new DuplicateDetectionEventException(request.eventId());
        }

        DetectionEvent event = new DetectionEvent(
                request.eventId(),
                request.cameraId(),
                request.capturedAt(),
                request.image().width(),
                request.image().height()
        );
        request.birds().forEach(bird -> event.addBird(new BirdDetection(
                bird.detectionId(),
                bird.trackId(),
                bird.speciesCode(),
                bird.detectionConfidence(),
                bird.classificationConfidence(),
                bird.bbox().x(),
                bird.bbox().y(),
                bird.bbox().width(),
                bird.bbox().height()
        )));

        try {
            detectionEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException exception) {
            if (isEventIdUniqueViolation(exception)) {
                throw new DuplicateDetectionEventException(request.eventId());
            }
            throw exception;
        }

        RiskDecisionEngine.RiskAssessment assessment = riskDecisionEngine.decide(request.birds());
        riskDecisionRepository.save(new RiskDecision(
                event,
                assessment.score(),
                assessment.level(),
                assessment.reason()
        ));

        String commandId = null;
        if (assessment.level() == RiskLevel.HIGH) {
            // TODO:
            // MVP에서는 cameraId를 deviceId로 임시 사용한다.
            // Camera-Device Mapping 테이블 추가 후 제거 예정.
            DeviceCommand command = deviceCommandRepository.save(new DeviceCommand(
                    "command-" + UUID.randomUUID(),
                    event,
                    request.cameraId(),
                    COMMAND_TYPE,
                    COMMAND_DURATION_MS,
                    DeviceCommandStatus.CREATED
            ));
            commandId = command.getCommandId();
        }

        return new DetectionEventResponse(
                event.getEventId(),
                assessment.score(),
                assessment.level(),
                commandId
        );
    }

    private boolean isEventIdUniqueViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("uk_detection_events_event_id")
                        || (normalized.contains("event_id") && normalized.contains("unique"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
