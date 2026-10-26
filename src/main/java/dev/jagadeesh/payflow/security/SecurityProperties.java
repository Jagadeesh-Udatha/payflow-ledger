package dev.jagadeesh.payflow.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("payflow.security")
public record SecurityProperties(String jwtSecret, Duration tokenTtl, String opsPassword, String auditorPassword,
                                 String allowedOrigin) {
}
