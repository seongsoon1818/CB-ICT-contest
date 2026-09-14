package com.animalguard.service;

import com.animalguard.config.ActuationProperties;
import com.animalguard.config.DeviceControlProperties;
import com.animalguard.config.OperatorApiProperties;
import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.DeviceCommandType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ManualCommandPreflightService {

    private final OperatorApiProperties operatorApiProperties;
    private final ActuationProperties actuationProperties;
    private final DeviceControlProperties deviceControlProperties;
    private final ActuationTransportReadiness transportReadiness;

    public boolean isOperatorApiEnabled() {
        return operatorApiProperties.enabled();
    }

    public boolean isKnownDevice(String deviceId) {
        return deviceControlProperties.cameraDeviceMappings().containsValue(deviceId);
    }

    public List<ActuationBlocker> blockersForCreation(DeviceCommandType commandType) {
        List<ActuationBlocker> blockers = new ArrayList<>();
        addCommandAndTransportBlockers(blockers, commandType);
        return List.copyOf(blockers);
    }

    public List<ActuationBlocker> blockersForDispatch(
            DeviceCommandType commandType,
            String deviceId
    ) {
        List<ActuationBlocker> blockers = new ArrayList<>();
        if (!operatorApiProperties.enabled()) {
            blockers.add(ActuationBlocker.OPERATOR_API_DISABLED);
        }
        if (isRotation(commandType) && !actuationProperties.enabled()) {
            blockers.add(ActuationBlocker.ACTUATION_DISABLED);
        }
        if (!isKnownDevice(deviceId)) {
            blockers.add(ActuationBlocker.CAMERA_UNMAPPED);
        }
        if (!transportReadiness.isReady()) {
            blockers.add(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
        }
        return List.copyOf(blockers);
    }

    private void addCommandAndTransportBlockers(
            List<ActuationBlocker> blockers,
            DeviceCommandType commandType
    ) {
        if (isRotation(commandType) && !actuationProperties.enabled()) {
            blockers.add(ActuationBlocker.ACTUATION_DISABLED);
        }
        if (!transportReadiness.isReady()) {
            blockers.add(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
        }
    }

    private boolean isRotation(DeviceCommandType commandType) {
        return commandType == DeviceCommandType.ROTATE_CAMERA_LEFT
                || commandType == DeviceCommandType.ROTATE_CAMERA_RIGHT;
    }
}
