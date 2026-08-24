package com.animalguard;

import com.animalguard.config.RiskProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RiskProperties.class)
public class AnimalGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnimalGuardApplication.class, args);
    }
}
