package com.animalguard.service;

import com.animalguard.config.OperatorApiProperties;
import com.animalguard.exception.OperatorAuthenticationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorTokenVerifierTest {

    private final OperatorTokenVerifier verifier = new OperatorTokenVerifier(
            new OperatorApiProperties(true, "fake-test-operator-token")
    );

    @Test
    void acceptsExactToken() {
        assertThatCode(() -> verifier.verify("fake-test-operator-token"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrDifferentTokenWithoutDisclosingEitherValue() {
        assertRejected(null);
        assertRejected("fake-test-invalid-token");
    }

    private void assertRejected(String presentedToken) {
        assertThatThrownBy(() -> verifier.verify(presentedToken))
                .isInstanceOf(OperatorAuthenticationException.class)
                .hasMessage("Operator authentication failed")
                .hasMessageNotContaining("fake-test-operator-token")
                .hasMessageNotContaining("fake-test-invalid-token");
    }
}
