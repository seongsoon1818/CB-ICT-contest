package com.animalguard.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorApiPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsToDisabledWithNoToken() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            OperatorApiProperties properties = context.getBean(OperatorApiProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.token()).isEmpty();
        });
    }

    @Test
    void rejectsEnabledApiWithoutNonBlankToken() {
        contextRunner
                .withPropertyValues("animalguard.operator-api.enabled=true")
                .run(context -> assertThat(context).hasFailed());

        contextRunner
                .withPropertyValues(
                        "animalguard.operator-api.enabled=true",
                        "animalguard.operator-api.token=   "
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsEnabledApiWithoutRenderingToken() {
        contextRunner
                .withPropertyValues(
                        "animalguard.operator-api.enabled=true",
                        "animalguard.operator-api.token=fake-test-operator-token"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OperatorApiProperties properties = context.getBean(OperatorApiProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.token()).isEqualTo("fake-test-operator-token");
                    assertThat(properties.toString())
                            .doesNotContain("fake-test-operator-token")
                            .contains("<redacted>");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OperatorApiProperties.class)
    static class TestConfiguration {
    }
}
