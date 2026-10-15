package com.alphaledger.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServerApplicationTest {

    @LocalServerPort
    private int port;

    @Test
    void healthIsUpAndRegistryApiResponds() {
        RestClient client = RestClient.create("http://localhost:" + port);

        String health = client.get().uri("/actuator/health").retrieve().body(String.class);
        String apps = client.get().uri("/eureka/apps").accept(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class);

        assertThat(health).contains("\"status\":\"UP\"");
        assertThat(apps).contains("applications");
    }
}
