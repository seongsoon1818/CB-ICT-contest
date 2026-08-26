package com.animalguard.repository;

import com.animalguard.domain.AnimalObservationState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnimalObservationStateRepository extends JpaRepository<AnimalObservationState, Long> {

    Optional<AnimalObservationState> findByCameraId(String cameraId);
}
