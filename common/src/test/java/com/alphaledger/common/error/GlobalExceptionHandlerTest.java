package com.alphaledger.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.alphaledger.common.web.CorrelationId;
import com.alphaledger.common.web.CorrelationIdFilter;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void mapsApiExceptionToItsStatusAndCode() throws Exception {
        mockMvc.perform(get("/missing").header(CorrelationId.HEADER, "corr-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Trade 42 not found"))
                .andExpect(jsonPath("$.path").value("/missing"))
                .andExpect(jsonPath("$.correlationId").value("corr-1"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void mapsBusinessRuleViolationTo422() throws Exception {
        mockMvc.perform(get("/rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void mapsBeanValidationErrorsTo400WithFieldMessages() throws Exception {
        mockMvc.perform(post("/trades").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tradeRef\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "quantity: must be greater than 0; tradeRef: must not be blank"))
                .andExpect(header().exists(CorrelationId.HEADER));
    }

    @Test
    void mapsMalformedJsonTo400() throws Exception {
        mockMvc.perform(post("/trades").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    void hidesDetailsOfUnexpectedErrors() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    record CreateTradeRequest(@NotBlank String tradeRef, @Positive int quantity) {
    }

    @RestController
    static class TestController {

        @GetMapping("/missing")
        String missing() {
            throw new NotFoundException("Trade 42 not found");
        }

        @GetMapping("/rule")
        String rule() {
            throw new BusinessRuleException("SELL exceeds current holdings");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("database password is hunter2");
        }

        @PostMapping("/trades")
        String create(@Valid @RequestBody CreateTradeRequest request) {
            return "ok";
        }
    }
}
