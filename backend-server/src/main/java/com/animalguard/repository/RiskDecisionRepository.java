package com.animalguard.repository;

import com.animalguard.domain.RiskDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskDecisionRepository extends JpaRepository<RiskDecision, Long> {
}
