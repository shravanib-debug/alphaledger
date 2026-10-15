package com.alphaledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
class ConfigServerApplicationTest {

    @LocalServerPort
    private int port;

    @Test
    void servesSharedConfigurationToServices() {
        String config = RestClient.create("http://localhost:" + port)
                .get().uri("/portfolio-service/default")
                .retrieve().body(String.class);

        assertThat(config)
                .contains("config-repo/application.yml")
                .contains("\"alphaledger.base-currency\":\"INR\"");
    }
}
