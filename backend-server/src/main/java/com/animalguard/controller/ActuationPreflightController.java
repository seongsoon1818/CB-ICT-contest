package com.animalguard.controller;

import com.animalguard.service.ActuationPreflight;
import com.animalguard.service.ActuationPreflightService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/actuation")
@RequiredArgsConstructor
public class ActuationPreflightController {

    private final ActuationPreflightService preflightService;

    @GetMapping("/preflight")
    public ActuationPreflight preflight() {
        return preflightService.evaluate();
    }
}
