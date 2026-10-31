package com.alphaledger.common.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Paths a service serves without a token, on top of the health, info and API-docs defaults. */
@ConfigurationProperties("alphaledger.security")
public record SecurityPathsProperties(List<String> additionalPublicPaths) {

    public static final List<String> DEFAULT_PUBLIC_PATHS = List.of(
            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            "/error");

    public SecurityPathsProperties {
        additionalPublicPaths = additionalPublicPaths == null ? List.of() : List.copyOf(additionalPublicPaths);
    }

    public String[] allPublicPaths() {
        return java.util.stream.Stream.concat(DEFAULT_PUBLIC_PATHS.stream(), additionalPublicPaths.stream())
                .toArray(String[]::new);
    }
}
