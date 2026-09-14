package com.animalguard.controller;

import com.animalguard.repository.DeviceCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ManualDeviceCommandDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeviceCommandRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void disabledOperatorApiReturnsNormalSuppressionWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/devices/pi-001/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "15356786-9588-4db4-a0fe-f8acd6300868",
                                  "command": "ROTATE_CAMERA_LEFT"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").doesNotExist())
                .andExpect(jsonPath("$.commandOutcome", is("SUPPRESSED")))
                .andExpect(jsonPath("$.commandBlockers[0]", is("OPERATOR_API_DISABLED")));

        assertThat(repository.count()).isZero();
    }
}
