package com.animalguard.service;

import com.animalguard.domain.AnimalDetection;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.RiskDecision;
import com.animalguard.domain.RiskLevel;
import com.animalguard.dto.DetectionEventRequest;
import com.animalguard.dto.DetectionEventResponse;
import com.animalguard.exception.DuplicateDetectionEventException;
import com.animalguard.repository.DeviceCommandRepository;
import com.animalguard.repository.DetectionEventRepository;
import com.animalguard.repository.RiskDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
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
            log.warn("Duplicate detection event rejected: eventId={}", request.eventId());
            throw new DuplicateDetectionEventException(request.eventId());
        }

        DetectionEvent event = new DetectionEvent(
                request.eventId(),
                request.cameraId(),
                request.capturedAt(),
                request.image().width(),
                request.image().height(),
                request.model().detectorVersion(),
                request.model().classifierVersion()
        );
        request.detections().forEach(detection -> event.addDetection(new AnimalDetection(
                detection.detectionId(),
                detection.trackId(),
                detection.classCode(),
                detection.detectionConfidence(),
                detection.classificationConfidence(),
                detection.bbox().x(),
                detection.bbox().y(),
                detection.bbox().width(),
                detection.bbox().height()
        )));

        try {
            detectionEventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException exception) {
            if (isEventIdUniqueViolation(exception)) {
                log.warn("Concurrent duplicate detection event rejected: eventId={}", request.eventId());
                throw new DuplicateDetectionEventException(request.eventId());
            }
            throw exception;
        }

        RiskDecisionEngine.RiskAssessment assessment = riskDecisionEngine.decide(request.detections());
        riskDecisionRepository.save(new RiskDecision(
                event,
                assessment.score(),
                assessment.level(),
                assessment.reason()
        ));

        String commandId = null;
        if (assessment.level() == RiskLevel.HIGH) {
            log.info("High risk detection event assessed: eventId={}, cameraId={}, riskScore={}",
                    request.eventId(), request.cameraId(), assessment.score());
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
            log.info("Device command created: commandId={}, eventId={}, deviceId={}, commandType={}",
                    commandId, request.eventId(), request.cameraId(), COMMAND_TYPE);
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
            if (cause instanceof ConstraintViolationException constraintViolation
                    && constraintViolation.getConstraintName() != null
                    && constraintViolation.getConstraintName()
                    .toLowerCase()
                    .contains("uk_detection_events_event_id")) {
                return true;
            }

            String message = cause.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("uk_detection_events_event_id")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }
}
