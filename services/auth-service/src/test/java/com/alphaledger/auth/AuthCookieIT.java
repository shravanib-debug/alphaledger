package com.alphaledger.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Browser (cookie) mode of login, refresh and logout against a real Postgres (docs/auth-session.md): cookie flags,
 * same-origin checks, expiry, concurrent refreshes (real row locks), lost-response replay, reuse detection and session
 * revocation. Each test uses its own demo user, so revoking one user's sessions does not affect another test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthCookieIT {

    private static final String PASSWORD = "Test-Password-123";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String COOKIE = "__Secure-al_refresh";   // Secure is on by default

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void loginSetsAHardenedRefreshCookieAndKeepsTheTokenOutOfTheBody() throws Exception {
        MvcResult result = mockMvc.perform(webLogin("priya.pm@alphaledger.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").doesNotHaveJsonPath())
                .andReturn();

        String setCookie = setCookie(result);
        assertThat(setCookie).startsWith(COOKIE + "=")
                .contains("; Path=/api/v1/auth", "; Secure", "; HttpOnly", "; SameSite=Strict")
                .containsPattern("; Max-Age=60479[89];");   // expires with the token: 7 days
        assertThat(cookieValue(result)).hasSizeGreaterThanOrEqualTo(43);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(cookieValue(result));
    }

    @Test
    void apiClientsKeepTheBodyFlowAndGetNoCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("vikram.auditor@alphaledger.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();

        // A cookie sent without X-Client: web is ignored: API mode needs the token in the body.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, "anything"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("refreshToken: must not be blank"));
    }

    @Test
    void refreshRotatesTheCookieAndReusingTheOldOneEndsEverySession() throws Exception {
        long user = insertUser("reuse@alphaledger.test");
        String first = cookieValue(mockMvc.perform(webLogin("reuse@alphaledger.test")).andReturn());
        String otherDevice = cookieValue(mockMvc.perform(webLogin("reuse@alphaledger.test")).andReturn());

        MvcResult rotated = mockMvc.perform(webRefresh(first, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotHaveJsonPath())
                .andReturn();
        String second = cookieValue(rotated);
        assertThat(second).isNotEqualTo(first);

        // Replaying the spent cookie: rejected, the browser told to drop it, and every session of the user revoked.
        MvcResult reuse = mockMvc.perform(webRefresh(first, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"))
                .andReturn();
        assertThat(setCookie(reuse)).startsWith(COOKIE + "=;").contains("Max-Age=0");
        mockMvc.perform(webRefresh(second, null)).andExpect(status().isUnauthorized());
        mockMvc.perform(webRefresh(otherDevice, null)).andExpect(status().isUnauthorized());
        assertThat(reasons(user)).contains("REUSE_DETECTED").doesNotContain("ACTIVE");
    }

    @Test
    void logoutRevokesTheTokenAndClearsTheCookie() throws Exception {
        String token = cookieValue(mockMvc.perform(webLogin("anita.admin@alphaledger.test")).andReturn());

        MvcResult logout = mockMvc.perform(web(post("/api/v1/auth/logout")).cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(setCookie(logout)).startsWith(COOKIE + "=;").contains("Max-Age=0", "HttpOnly", "SameSite=Strict");
        mockMvc.perform(webRefresh(token, null)).andExpect(status().isUnauthorized());

        // Logging out without a cookie still clears it and succeeds.
        mockMvc.perform(web(post("/api/v1/auth/logout"))).andExpect(status().isNoContent());
    }

    @Test
    void crossSiteAndOriginlessCookieRequestsAreRejectedWithoutTouchingTheToken() throws Exception {
        String token = cookieValue(mockMvc.perform(webLogin("priya.pm@alphaledger.test")).andReturn());

        mockMvc.perform(webRefresh(token, null).header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_REJECTED"));
        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Client", "web").cookie(new Cookie(COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))   // no Origin
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Client", "web").header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "cross-site").cookie(new Cookie(COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        // A repeated header cannot smuggle a cross-site request or a second origin past the check.
        mockMvc.perform(webRefresh(token, null).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mockMvc.perform(webRefresh(token, null).header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
        mockMvc.perform(web(post("/api/v1/auth/logout")).header(HttpHeaders.ORIGIN, "null")
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(web(post("/api/v1/auth/login")).header(HttpHeaders.ORIGIN, "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content(credentials("priya.pm@alphaledger.test")))
                .andExpect(status().isForbidden());
        // A simple cross-site form post cannot set a JSON content type.
        mockMvc.perform(post("/api/v1/auth/refresh").header("X-Client", "web").header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie(COOKIE, token))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=b"))
                .andExpect(status().isUnsupportedMediaType());

        // None of the rejected requests spent or revoked the token.
        mockMvc.perform(webRefresh(token, null)).andExpect(status().isOk());
    }

    @Test
    void anExpiredCookieIsRejectedAndCleared() throws Exception {
        String token = cookieValue(mockMvc.perform(webLogin("vikram.auditor@alphaledger.test")).andReturn());
        jdbc.update("UPDATE refresh_token SET expires_at = ? WHERE user_id = 4 AND NOT revoked",
                Timestamp.from(Instant.now().minusSeconds(1)));

        MvcResult expired = mockMvc.perform(webRefresh(token, null))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(setCookie(expired)).contains("Max-Age=0");
        mockMvc.perform(webRefresh("", null)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshesOfOneCookieNeverForkTheChain() throws Exception {
        long user = insertUser("race.one@alphaledger.test");
        String token = cookieValue(mockMvc.perform(webLogin("race.one@alphaledger.test")).andReturn());

        // Eight tabs refresh the same cookie at once, without coordination: the row lock serialises them; the first
        // rotates, the rest present a spent token, which is reuse.
        List<Integer> statuses = concurrently(8, () -> mockMvc.perform(webRefresh(token, null)).andReturn()
                .getResponse().getStatus());

        assertThat(statuses).containsOnly(200, 401);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(activeTokens(user)).isZero();   // reuse ended the session, including the winner's new token
    }

    @Test
    void concurrentRetriesOfOneAttemptLeaveExactlyOneValidToken() throws Exception {
        long user = insertUser("race.two@alphaledger.test");
        String token = cookieValue(mockMvc.perform(webLogin("race.two@alphaledger.test")).andReturn());
        String key = "web-attempt-7f3a2c1d";

        List<Integer> statuses = concurrently(5, () -> mockMvc.perform(webRefresh(token, key)).andReturn()
                .getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        assertThat(activeTokens(user)).isEqualTo(1);
    }

    @Test
    void aLostResponseCanBeRetriedWithItsKeyButTheLostTokenIsDead() throws Exception {
        String token = cookieValue(mockMvc.perform(webLogin("anita.admin@alphaledger.test")).andReturn());
        String key = "web-attempt-91b0e4aa";

        String lost = cookieValue(mockMvc.perform(webRefresh(token, key)).andExpect(status().isOk()).andReturn());
        String retried = cookieValue(mockMvc.perform(webRefresh(token, key)).andExpect(status().isOk()).andReturn());
        assertThat(retried).isNotEqualTo(lost);

        mockMvc.perform(webRefresh(retried, "web-attempt-91b0e4ab")).andExpect(status().isOk());
        // The same old cookie with a different key is reuse, not a retry.
        mockMvc.perform(webRefresh(token, "web-attempt-00000000")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedIdempotencyKeyIsRefused() throws Exception {
        String token = cookieValue(mockMvc.perform(webLogin("rohan.ops@alphaledger.test")).andReturn());
        mockMvc.perform(webRefresh(token, "short")).andExpect(status().isBadRequest());
        mockMvc.perform(webRefresh(token, "has spaces in it, sixteen+")).andExpect(status().isBadRequest());
    }

    @Test
    void disablingAUserRevokesTheirSessionsAtTheNextRefresh() throws Exception {
        long user = insertUser("leaver@alphaledger.test");
        String a = cookieValue(mockMvc.perform(webLogin("leaver@alphaledger.test")).andReturn());
        String b = cookieValue(mockMvc.perform(webLogin("leaver@alphaledger.test")).andReturn());
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", user);

        mockMvc.perform(webRefresh(a, null)).andExpect(status().isUnauthorized());
        assertThat(activeTokens(user)).isZero();
        mockMvc.perform(webRefresh(b, null)).andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private MockHttpServletRequestBuilder web(MockHttpServletRequestBuilder request) {
        return request.header("X-Client", "web").header(HttpHeaders.ORIGIN, ORIGIN).header("Sec-Fetch-Site", "same-origin");
    }

    private MockHttpServletRequestBuilder webLogin(String email) {
        return web(post("/api/v1/auth/login")).contentType(MediaType.APPLICATION_JSON).content(credentials(email));
    }

    private MockHttpServletRequestBuilder webRefresh(String token, String key) {
        MockHttpServletRequestBuilder request = web(post("/api/v1/auth/refresh")).cookie(new Cookie(COOKIE, token))
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private static String credentials(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private static String setCookie(MvcResult result) {
        List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(headers).hasSize(1);
        return headers.get(0);
    }

    private static String cookieValue(MvcResult result) {
        String header = setCookie(result);
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    /** A user with the demo password (hash copied from a seeded user), so tests can own their sessions. */
    private long insertUser(String email) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (email, password_hash, full_name, role)
                SELECT ?, password_hash, 'Test user', 'PM' FROM app_user WHERE id = 2 RETURNING id
                """, Long.class, email);
    }

    private long activeTokens(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE user_id = ? AND NOT revoked "
                + "AND expires_at > now()", Long.class, userId);
    }

    private List<String> reasons(long userId) {
        return jdbc.queryForList("SELECT coalesce(revoked_reason, 'ACTIVE') FROM refresh_token WHERE user_id = ?",
                String.class, userId);
    }

    private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
