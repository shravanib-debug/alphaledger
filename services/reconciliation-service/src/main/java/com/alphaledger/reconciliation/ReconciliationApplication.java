package com.alphaledger.reconciliation;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReconciliationApplication {

    public static void main(String[] args) {
        // All services run in UTC; dates and timestamps are stored and exchanged in UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
