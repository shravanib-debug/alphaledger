package com.alphaledger.common.web;

import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Writes {@code java.sql.Date} as {@code yyyy-MM-dd}. Endpoints that return database rows as maps
 * ({@code JdbcTemplate.queryForList}) carry DATE columns as {@code java.sql.Date}, which Jackson otherwise writes as
 * a midnight-UTC timestamp ({@code 2026-10-15T00:00:00.000Z}); a calendar date has no time or zone, so it must not
 * gain one. {@code java.sql.Date.toString()} is the ISO date. Timestamps are unchanged.
 */
public class SqlDateJsonModule extends SimpleModule {

    public SqlDateJsonModule() {
        super("alphaledger-sql-date");
        addSerializer(java.sql.Date.class, ToStringSerializer.instance);
    }
}
