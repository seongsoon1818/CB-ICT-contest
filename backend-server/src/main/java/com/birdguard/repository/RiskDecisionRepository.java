package com.birdguard.repository;

import com.birdguard.domain.RiskDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskDecisionRepository extends JpaRepository<RiskDecision, Long> {
}
