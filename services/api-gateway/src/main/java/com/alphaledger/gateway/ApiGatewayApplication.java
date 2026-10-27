package com.alphaledger.gateway;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApiGatewayApplication {

    public static void main(String[] args) {
        // All services run in UTC; dates and timestamps are stored and exchanged in UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
