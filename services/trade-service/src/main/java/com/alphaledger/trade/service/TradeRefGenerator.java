package com.alphaledger.trade.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Generates trade references in the documented format TRD-yyyyMMdd-NNNN (docs/conventions.md §7). */
@Component
public class TradeRefGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;

    public TradeRefGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next(LocalDate tradeDate) {
        Long sequence = jdbc.queryForObject("SELECT nextval('trade_ref_seq')", Long.class);
        return format(tradeDate, sequence);
    }

    static String format(LocalDate tradeDate, long sequence) {
        return "TRD-" + tradeDate.format(DATE) + "-" + String.format("%04d", sequence);
    }
}
