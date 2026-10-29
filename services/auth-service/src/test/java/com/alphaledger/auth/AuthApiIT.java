package com.alphaledger.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jayway.jsonpath.JsonPath;

/** End-to-end auth API against a real Postgres (Flyway migrations + demo users). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthApiIT {

    private static final String PASSWORD = "Test-Password-123";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "{0} logs in as {1}")
    @CsvSource({
            "anita.admin@alphaledger.test,    ADMIN",
            "priya.pm@alphaledger.test,       PM",
            "rohan.ops@alphaledger.test,      OPS_ANALYST",
            "vikram.auditor@alphaledger.test, AUDITOR"
    })
    void eachDemoUserCanLogIn(String email, String role) throws Exception {
        mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value(role));
    }

    @Test
    void wrongPasswordReturns401InStandardErrorFormat() throws Exception {
        mockMvc.perform(login("priya.pm@alphaledger.test", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/login"));
    }

    @Test
    void meRequiresAValidTokenAndReturnsTheCaller() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        String token = accessToken("rohan.ops@alphaledger.test");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.email").value("rohan.ops@alphaledger.test"));
    }

    @Test
    void refreshRotatesTheTokenAndTheOldOneCannotBeReused() throws Exception {
        String refresh = field(mockMvc.perform(login("priya.pm@alphaledger.test", PASSWORD)).andReturn(),
                "$.refreshToken");

        MvcResult rotated = mockMvc.perform(refresh(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        String newRefresh = field(rotated, "$.refreshToken");
        assertThat(newRefresh).isNotEqualTo(refresh);

        // Reusing the old token fails and, as a theft signal, revokes the new one too.
        mockMvc.perform(refresh(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
        mockMvc.perform(refresh(newRefresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String refresh = field(mockMvc.perform(login("vikram.auditor@alphaledger.test", PASSWORD)).andReturn(),
                "$.refreshToken");

        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(refresh(refresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminCanCreateUsers() throws Exception {
        String body = """
                {"email":"new.analyst@alphaledger.test","password":"Long-Enough-Pass-1",
                 "fullName":"New Analyst","role":"OPS_ANALYST"}""";

        mockMvc.perform(post("/api/v1/auth/users").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + accessToken("priya.pm@alphaledger.test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        String admin = "Bearer " + accessToken("anita.admin@alphaledger.test");
        mockMvc.perform(post("/api/v1/auth/users").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", admin))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.role").value("OPS_ANALYST"))
                .andExpect(jsonPath("$.password").doesNotExist());
        mockMvc.perform(post("/api/v1/auth/users").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", admin))
                .andExpect(status().isConflict());
        mockMvc.perform(login("new.analyst@alphaledger.test", "Long-Enough-Pass-1"))
                .andExpect(status().isOk());
    }

    @Test
    void anySignedInUserCanReadTheDirectoryButNotTheUserAdministration() throws Exception {
        String ops = "Bearer " + accessToken("rohan.ops@alphaledger.test");

        mockMvc.perform(get("/api/v1/auth/users/directory").header("Authorization", ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 3)].fullName").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id == 3)].role").value("OPS_ANALYST"))
                .andExpect(jsonPath("$[?(@.id == 3)].active").value(true))
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").doesNotExist());
        mockMvc.perform(get("/api/v1/auth/users/directory")).andExpect(status().isUnauthorized());

        // The full user list, with emails, stays Admin-only.
        mockMvc.perform(get("/api/v1/auth/users").header("Authorization", ops)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/auth/users/3").header("Authorization", ops)).andExpect(status().isForbidden());
    }

    @Test
    void createUserValidatesInput() throws Exception {
        mockMvc.perform(post("/api/v1/auth/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"\",\"role\":null}")
                        .header("Authorization", "Bearer " + accessToken("anita.admin@alphaledger.test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    private String accessToken(String email) throws Exception {
        return field(mockMvc.perform(login(email, PASSWORD)).andExpect(status().isOk()).andReturn(), "$.accessToken");
    }

    private static org.springframework.test.web.servlet.RequestBuilder login(String email, String password) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private static org.springframework.test.web.servlet.RequestBuilder refresh(String token) {
        return post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}");
    }

    private static String field(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
