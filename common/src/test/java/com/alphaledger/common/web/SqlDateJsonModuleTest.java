package com.alphaledger.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class SqlDateJsonModuleTest {

    @Test
    void sqlDatesAreWrittenAsIsoCalendarDates() {
        JsonMapper mapper = JsonMapper.builder().addModule(new SqlDateJsonModule()).build();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("as_of_date", Date.valueOf(LocalDate.of(2026, 10, 15)));
        row.put("created_at", Timestamp.from(Instant.parse("2026-10-15T09:30:00Z")));

        String json = mapper.writeValueAsString(row);

        assertThat(json).contains("\"as_of_date\":\"2026-10-15\"");
        assertThat(json).doesNotContain("2026-10-15T00:00");   // no invented midnight-UTC time
        assertThat(json).contains("\"created_at\":");          // timestamps keep their existing format
    }

    @Test
    void withoutTheModuleTheDateGainsATime() {
        // Documents the bug the module fixes.
        String json = JsonMapper.builder().build().writeValueAsString(Map.of("d", Date.valueOf("2026-10-15")));
        assertThat(json).doesNotContain("\"d\":\"2026-10-15\"");
    }
}
