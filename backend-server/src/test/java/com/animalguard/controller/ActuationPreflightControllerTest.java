package com.animalguard.controller;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.service.ActuationPreflight;
import com.animalguard.service.ActuationPreflightService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ActuationPreflightControllerTest {

    @Mock
    private ActuationPreflightService preflightService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ActuationPreflightController(preflightService)).build();
    }

    @Test
    void returnsBlockedPreflightWithOkStatus() throws Exception {
        when(preflightService.evaluate()).thenReturn(new ActuationPreflight(
                false,
                false,
                List.of(
                        ActuationBlocker.ACTUATION_DISABLED,
                        ActuationBlocker.RISK_POLICY_UNCONFIRMED,
                        ActuationBlocker.RESPONSE_POLICY_DISABLED,
                        ActuationBlocker.MQTT_PUBLISHER_NOT_READY
                )
        ));

        mockMvc.perform(get("/api/v1/actuation/preflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)))
                .andExpect(jsonPath("$.ready", is(false)))
                .andExpect(jsonPath("$.blockers[0]", is("ACTUATION_DISABLED")))
                .andExpect(jsonPath("$.blockers[1]", is("RISK_POLICY_UNCONFIRMED")))
                .andExpect(jsonPath("$.blockers[2]", is("RESPONSE_POLICY_DISABLED")))
                .andExpect(jsonPath("$.blockers[3]", is("MQTT_PUBLISHER_NOT_READY")));
    }

    @Test
    void returnsReadyPreflightWithOkStatus() throws Exception {
        when(preflightService.evaluate()).thenReturn(new ActuationPreflight(true, true, List.of()));

        mockMvc.perform(get("/api/v1/actuation/preflight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.ready", is(true)))
                .andExpect(jsonPath("$.blockers").isEmpty());
    }
}
