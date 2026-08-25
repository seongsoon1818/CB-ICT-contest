package com.animalguard.service;

import com.animalguard.config.ActuationProperties;
import com.animalguard.config.DeviceControlProperties;
import com.animalguard.domain.ActuationBlocker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ActuationPreflightService {

    private final ActuationProperties actuationProperties;
    private final DeviceControlProperties deviceControlProperties;
    private final ActuationTransportReadiness transportReadiness;

    public ActuationPreflight evaluate() {
        List<ActuationBlocker> blockers = new ArrayList<>();
        if (!actuationProperties.enabled()) {
            blockers.add(ActuationBlocker.ACTUATION_DISABLED);
        }
        if (!actuationProperties.riskPolicyConfirmed()) {
            blockers.add(ActuationBlocker.RISK_POLICY_UNCONFIRMED);
        }
        if (deviceControlProperties.cameraDeviceMappings().isEmpty()) {
            blockers.add(ActuationBlocker.CAMERA_DEVICE_MAPPING_EMPTY);
        }
        if (!transportReadiness.isReady()) {
            blockers.add(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
        }
        return new ActuationPreflight(
                actuationProperties.enabled(),
                blockers.isEmpty(),
                blockers
        );
    }
}
