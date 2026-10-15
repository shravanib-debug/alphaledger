package com.alphaledger.position;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PositionApplication {

    public static void main(String[] args) {
        // All services run in UTC; dates and timestamps are stored and exchanged in UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(PositionApplication.class, args);
    }
}
