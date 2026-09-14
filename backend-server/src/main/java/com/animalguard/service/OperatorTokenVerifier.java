package com.animalguard.service;

import com.animalguard.config.OperatorApiProperties;
import com.animalguard.exception.OperatorAuthenticationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
public class OperatorTokenVerifier {

    private final OperatorApiProperties properties;

    public void verify(String presentedToken) {
        byte[] expected = properties.token().getBytes(StandardCharsets.UTF_8);
        byte[] presented = (presentedToken == null ? "" : presentedToken)
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, presented)) {
            throw new OperatorAuthenticationException();
        }
    }
}
