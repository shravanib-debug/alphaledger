package com.alphaledger.portfolio;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Portfolio and instrument APIs against a real Postgres, with real signed tokens. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PortfolioApiIT {

    private static final String ADMIN = TestTokens.bearer(1, "ADMIN");
    private static final String PRIYA = TestTokens.bearer(2, "PM");
    private static final String OPS = TestTokens.bearer(3, "OPS_ANALYST");
    private static final String AUDITOR = TestTokens.bearer(4, "AUDITOR");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsMissingExpiredForeignAndWrongIssuerTokens() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/portfolios")
                        .header("Authorization", TestTokens.bearer(2, "PM", "alphaledger-auth", Instant.now().minusSeconds(300))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/portfolios")
                        .header("Authorization", TestTokens.bearer(2, "PM", "someone-else", Instant.now().plusSeconds(600))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/portfolios").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyRoleSeesTheSeededDemoPortfolios() throws Exception {
        for (String token : new String[] {ADMIN, PRIYA, OPS, AUDITOR}) {
            mockMvc.perform(get("/api/v1/portfolios/1").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("AlphaLedger India Credit Opportunities Fund I"))
                    .andExpect(jsonPath("$.managerId").value(2))
                    .andExpect(jsonPath("$.baseCurrency").value("INR"))
                    .andExpect(jsonPath("$.funds.length()").value(2));
        }
        mockMvc.perform(get("/api/v1/portfolios").param("status", "ACTIVE").header("Authorization", AUDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name").value(hasItem("AlphaLedger Bharat Real Assets Fund")))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void readOnlyRolesCannotChangePortfolios() throws Exception {
        for (String token : new String[] {OPS, AUDITOR}) {
            mockMvc.perform(post("/api/v1/portfolios").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Not allowed\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        }
    }

    @Test
    void pmManagesOwnPortfoliosOnly() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios").header("Authorization", PRIYA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Priya Test Fund\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.managerId").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // A portfolio an admin creates for another manager is not Priya's to change.
        String otherId = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Someone Else's Fund\",\"managerId\":77}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id").toString();
        mockMvc.perform(put("/api/v1/portfolios/" + otherId).header("Authorization", PRIYA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/portfolios/2/funds").header("Authorization", PRIYA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Infrastructure Debt Sleeve\",\"assetClass\":\"REAL_ESTATE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetClass").value("REAL_ESTATE"));
    }

    @Test
    void validatesPortfolioInput() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios").header("Authorization", PRIYA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"USD Fund\",\"baseCurrency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("baseCurrency: must be INR (single base currency in v1)"));
        mockMvc.perform(post("/api/v1/portfolios").header("Authorization", PRIYA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AlphaLedger Bharat Real Assets Fund\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/portfolios").param("size", "500").header("Authorization", PRIYA))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/portfolios/999999").header("Authorization", PRIYA))
                .andExpect(status().isNotFound());
    }

    @Test
    void instrumentMasterIsLoadedWithStableIds() throws Exception {
        mockMvc.perform(get("/api/v1/instruments").header("Authorization", AUDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(29));
        mockMvc.perform(get("/api/v1/instruments/1").header("Authorization", AUDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isin").value("INE041025011"))
                .andExpect(jsonPath("$.symbol").value("EMBASSY"))
                .andExpect(jsonPath("$.instrumentType").value("REIT"));
        mockMvc.perform(get("/api/v1/instruments").param("assetClass", "REAL_ESTATE").header("Authorization", OPS))
                .andExpect(jsonPath("$.totalElements").value(8));
        mockMvc.perform(get("/api/v1/instruments").param("isin", "ine002a01018").header("Authorization", OPS))
                .andExpect(jsonPath("$.content[0].symbol").value("RELIANCE"));
        mockMvc.perform(get("/api/v1/instruments").param("q", "flexi cap").header("Authorization", OPS))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void createInstrumentChecksIsinRules() throws Exception {
        String infosysAgain = instrument("INE009A01021", false, "EQUITY", "LISTED_EQUITY");
        String wrongCheckDigit = instrument("INE154A01026", false, "EQUITY", "LISTED_EQUITY");
        String fakeSynthetic = instrument("INE154A01025", true, "PRIVATE", "SYNTHETIC_PRIVATE");
        String itc = instrument("INE154A01025", false, "EQUITY", "LISTED_EQUITY");

        mockMvc.perform(post("/api/v1/instruments").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(infosysAgain)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/instruments").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongCheckDigit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("isin: check digit is wrong for INE154A01026"));
        mockMvc.perform(post("/api/v1/instruments").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(fakeSynthetic)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/instruments").header("Authorization", AUDITOR)
                .contentType(MediaType.APPLICATION_JSON).content(itc)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/instruments").header("Authorization", PRIYA)
                        .contentType(MediaType.APPLICATION_JSON).content(itc))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.greaterThanOrEqualTo(10000)))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    private static String instrument(String isin, boolean synthetic, String type, String proxy) {
        return """
                {"isin":"%s","symbol":"TEST","exchange":"NSE","name":"Test instrument","assetClass":"PRIVATE_EQUITY",
                 "assetClassProxy":"%s","instrumentType":"%s","synthetic":%s}""".formatted(isin, proxy, type, synthetic);
    }
}
