package com.animalguard.service;

import com.animalguard.domain.AnimalDetection;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.RiskDecision;
import com.animalguard.dto.DetectionEventRequest;
import com.animalguard.dto.DetectionEventResponse;
import com.animalguard.exception.DuplicateDetectionEventException;
import com.animalguard.repository.DetectionEventRepository;
import com.animalguard.repository.RiskDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DetectionEventService {

    private final DetectionEventRepository detectionEventRepository;
    private final RiskDecisionRepository riskDecisionRepository;
    private final RiskDecisionEngine riskDecisionEngine;
    private final AnimalObservationService animalObservationService;

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

        boolean animalPresent = !request.detections().isEmpty();
        AnimalObservationResult observationResult = animalObservationService.process(
                event,
                request.cameraId(),
                request.capturedAt(),
                animalPresent
        );
        CommandDecision commandDecision = observationResult.commandDecision();

        log.info(
                "Observation decision completed: eventId={}, cameraId={}, capturedAt={}, animalPresent={}, "
                        + "observationState={}, observationTrigger={}, commandType={}, commandOutcome={}, "
                        + "commandId={}, commandBlockers={}",
                event.getEventId(),
                request.cameraId(),
                request.capturedAt(),
                animalPresent,
                observationResult.presenceState(),
                observationResult.trigger(),
                observationResult.commandType(),
                commandDecision.outcome(),
                commandDecision.commandId(),
                commandDecision.blockers()
        );

        return new DetectionEventResponse(
                event.getEventId(),
                assessment.score(),
                assessment.level(),
                commandDecision.commandId(),
                commandDecision.outcome(),
                commandDecision.blockers()
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
