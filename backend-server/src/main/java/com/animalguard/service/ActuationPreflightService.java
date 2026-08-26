package com.animalguard.service;

import com.animalguard.config.ActuationProperties;
import com.animalguard.config.DeviceControlProperties;
import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.DeviceCommandType;
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
        List<ActuationBlocker> blockers = globalBlockers();
        return new ActuationPreflight(
                actuationProperties.enabled(),
                blockers.isEmpty(),
                blockers
        );
    }

    public List<ActuationBlocker> blockersForAutomaticCommand(DeviceCommandType commandType) {
        if (commandType == DeviceCommandType.STOP_DETERRENT) {
            List<ActuationBlocker> blockers = new ArrayList<>();
            addMappingAndTransportBlockers(blockers);
            return List.copyOf(blockers);
        }
        return List.copyOf(globalBlockers());
    }

    public List<ActuationBlocker> blockersForAutomaticDispatch(
            DeviceCommandType commandType,
            String deviceId
    ) {
        List<ActuationBlocker> blockers = new ArrayList<>(blockersForAutomaticCommand(commandType));
        if (!deviceControlProperties.cameraDeviceMappings().isEmpty()
                && !deviceControlProperties.cameraDeviceMappings().containsValue(deviceId)) {
            blockers.add(ActuationBlocker.CAMERA_UNMAPPED);
        }
        return List.copyOf(blockers);
    }

    private List<ActuationBlocker> globalBlockers() {
        List<ActuationBlocker> blockers = new ArrayList<>();
        if (!actuationProperties.enabled()) {
            blockers.add(ActuationBlocker.ACTUATION_DISABLED);
        }
        if (!actuationProperties.riskPolicyConfirmed()) {
            blockers.add(ActuationBlocker.RISK_POLICY_UNCONFIRMED);
        }
        addMappingAndTransportBlockers(blockers);
        return blockers;
    }

    private void addMappingAndTransportBlockers(List<ActuationBlocker> blockers) {
        if (deviceControlProperties.cameraDeviceMappings().isEmpty()) {
            blockers.add(ActuationBlocker.CAMERA_DEVICE_MAPPING_EMPTY);
        }
        if (!transportReadiness.isReady()) {
            blockers.add(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
        }
    }
}
