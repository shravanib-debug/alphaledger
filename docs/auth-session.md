# Browser Sessions: Refresh Token in an HttpOnly Cookie (M0)

> How the dashboard keeps a user signed in without any script on the page ever seeing the long-lived refresh token,
> and how refresh rotation, revocation and reuse detection behave under concurrent and retried requests. Implemented
> in auth-service (`AuthController`, `BrowserSession`, `RefreshTokenService`); contract for the frontend in §6.

## 1. Summary

| | Before | After |
|---|---|---|
| Access token (JWT, 15 min) | Response body; client sends `Authorization: Bearer` | Unchanged. The dashboard keeps it **in memory only** |
| Refresh token (opaque, 7 days) | Response body; client sends it in the body | **API clients:** unchanged. **Browsers** (`X-Client: web`): only in an `HttpOnly; SameSite=Strict` cookie scoped to `/api/v1/auth`, never in a body |
| Rotation | Every refresh issues a new token and revokes the old one | Unchanged, now recorded as a chain (`replaced_by`) with a reason |
| Reuse detection | A revoked token presented again revokes all of the user's tokens | Unchanged, and now also correct under concurrency (row lock) |
| Concurrent refresh of one token | **Both could succeed** (no lock): two live chains, reuse undetected | Serialised by `SELECT … FOR UPDATE`; the second is reuse |
| Lost response | Retrying meant presenting a spent token, so reuse logged the user out everywhere | Optional `Idempotency-Key` makes a retry of *the same attempt* safe (§4) |

Postman, curl, `scripts/smoke-test.sh`, `scripts/demo-trades.sh` and every service test keep using the body flow
unchanged: a request without `X-Client: web` behaves exactly as before.

## 2. Review of the previous implementation

Findings that shaped the change:

1. **Race in `consume()`.** The token was read without a lock, checked for `revoked = false`, then revoked. Two
   simultaneous refreshes with the same token both passed the check and both issued successors: a forked chain, and
   the reuse detector never fired. Fixed with a pessimistic row lock on the token (and on its successor during a
   replay).
2. **No record of why or by what a token was revoked.** Rotation and logout were indistinguishable, so no replay rule
   could be expressed safely. Added `revoked_reason`, `revoked_at`, `replaced_by`, `rotation_key_hash` (V3).
3. **A disabled user's refresh left the new token active.** Rotation happened before the user check, in a transaction
   that does not roll back on `InvalidRefreshTokenException`. Now every token of a disabled user is revoked.
4. `/refresh` and `/logout` are public paths (the refresh token is the credential); that stays, with the browser
   checks below in front of the cookie.

## 3. Cookie mode

### 3.1 The cookie

```
Set-Cookie: __Secure-al_refresh=<token>; Path=/api/v1/auth; Max-Age=<seconds left>; Expires=…; Secure; HttpOnly; SameSite=Strict
```

| Attribute | Why |
|---|---|
| `HttpOnly` | Script on the page (including injected script) cannot read it |
| `Secure` + `__Secure-` prefix | Sent only over HTTPS; the prefix makes the browser refuse the cookie unless it was set over HTTPS. `alphaledger.auth.session.cookie-secure` defaults to **true**; only the local plain-HTTP compose stack sets `AUTH_COOKIE_SECURE=false` (the cookie is then named `al_refresh`) |
| `SameSite=Strict` | Never sent on a request started by another site |
| `Path=/api/v1/auth` | Sent only to the auth endpoints, never to business APIs |
| `Max-Age` | Expires with the token itself (7 days from issue); cleared with `Max-Age=0` on logout and on any rejected refresh |

Only the refresh token is a cookie. The access token stays a `Bearer` header, so cookies never authorise a business
API call and the CSRF surface is limited to the three auth endpoints.

### 3.2 Opting in, and the cross-site checks

A request uses cookie mode only when it sends `X-Client: web`. Every cookie-mode request (login, refresh, logout) must
pass, in `BrowserSession.verifySameOrigin`:

1. `Sec-Fetch-Site` (sent by current browsers): no value may be `cross-site`.
2. `Origin` present, exactly once, and an exact match for one of `alphaledger.auth.session.allowed-origins`
   (default `http://localhost:3000,http://localhost:5173`).

Failure: `403 CSRF_REJECTED`, and the token is not touched. Further layers:

- `X-Client` is a custom header: a page on another origin cannot send it without a CORS preflight, which the gateway
  refuses for origins outside `FRONTEND_ORIGINS`.
- Bodies are JSON only; a cross-site HTML form (`application/x-www-form-urlencoded`) gets `415`.
- `SameSite=Strict` already withholds the cookie from cross-site requests.
- Even a forged refresh that got through would return the new access token in a response body the attacking page
  cannot read (CORS), and would rotate the user's cookie, which the attacker never sees.

Requests **without** `X-Client: web` ignore any cookie and require the token in the body (API mode).

## 4. Rotation, reuse and replay

### 4.1 Rules

`RefreshTokenService.rotate(token, idempotencyKey)` locks the presented token's row, then:

| State of the presented token | Result |
|---|---|
| Unknown, blank | `401` |
| Active but expired | `401` (nothing revoked) |
| Active | Rotate: issue successor S, mark token `ROTATED`, `replaced_by = S`, `rotation_key_hash = SHA-256(key)` if a key was sent |
| `ROTATED`, **and** same key hash, **and** less than `replay-window` (30 s) since rotation, **and** its current successor is still active and unused | **Replay**: mark that successor `REPLAY_SUPERSEDED`, issue S′, repoint `replaced_by = S′`, return S′ |
| Anything else that is revoked (`ROTATED` without a matching key, after the window, successor already used; `LOGOUT`; `REUSE_DETECTED`; `REPLAY_SUPERSEDED`) | **Reuse**: revoke every active token of the user (`REUSE_DETECTED`), `401` |

### 4.2 Why replay is not a grace period

A grace period accepts *any* presentation of a just-rotated token for a few seconds, so a stolen token used quickly
after the real user's refresh would get a live session. The replay rule is narrower, and each condition closes a gap:

| Condition | What it prevents |
|---|---|
| **Same `Idempotency-Key`** (random per refresh attempt, generated in the tab, sent only as a header, never stored by the browser beyond that attempt; the server keeps only its SHA-256) | A thief holding the old token does not know the key, so their attempt is reuse and ends every session |
| **Window ≤ 30 s** (configurable 0–120 s; 0 disables replay) | Bounds how long an in-flight attempt can be retried |
| **Successor unused** | If the client did receive and use the successor, the old token can never be replayed: that is reuse |
| **Supersede, don't duplicate** | The replay revokes the unused successor before issuing S′, so at most one token of the chain is ever valid |

Storage: `rotation_key_hash` (SHA-256 of the key) and `replaced_by` live on the spent token's row. No raw token is
stored anywhere; a database leak reveals neither tokens nor keys.

Residual risk, accepted: an attacker able to read the in-flight request (the token *and* its `Idempotency-Key`) within
30 s could replay it. That attacker already holds a valid token pair from the same request, and HTTPS (with `Secure`
cookies) is the control against it. Same-tab script injection can make authenticated requests anyway; the cookie
design limits that to the life of the page and keeps the refresh token unreadable.

### 4.3 Concurrency

- Two refreshes of one token **without** coordination: the row lock makes the second wait; it then sees `ROTATED`
  without a matching key: reuse, every session revoked (`AuthCookieIT.concurrentRefreshesOfOneCookieNeverForkTheChain`).
  This is deliberate: the server cannot tell a second tab from a thief, so the browser must coordinate (§6).
- Retries of **one attempt** (same key) in parallel: each waits for the lock, each replays and supersedes the
  previous successor; exactly one token is valid afterwards
  (`concurrentRetriesOfOneAttemptLeaveExactlyOneValidToken`).

## 5. Endpoint contract

| Endpoint | API mode (no `X-Client`) | Cookie mode (`X-Client: web`, `Origin`) |
|---|---|---|
| `POST /api/v1/auth/login` `{email, password}` | `200 {accessToken, tokenType, expiresIn, refreshToken, user}` | `200` same body **without** `refreshToken`; `Set-Cookie` |
| `POST /api/v1/auth/refresh` | Body `{refreshToken}` (missing → `400`); optional `Idempotency-Key` | Body `{}`; token from the cookie; optional `Idempotency-Key`; new `Set-Cookie`; on `401` the cookie is cleared |
| `POST /api/v1/auth/logout` | Body `{refreshToken}`; `204` | Token from the cookie (if any) revoked; cookie cleared; `204` |
| Errors | `400 VALIDATION_ERROR`, `401 INVALID_REFRESH_TOKEN` | Plus `403 CSRF_REJECTED`, `415` for non-JSON |

`Idempotency-Key`: 16–128 characters `[A-Za-z0-9_-]`; anything else is `400`.

## 6. Frontend contract (Phase 8, M2)

- **Login:** `POST /auth/login` with `X-Client: web` and `credentials: 'same-origin'` (Axios `withCredentials`), keep
  `accessToken` and `user` in memory.
- **Every API call:** `Authorization: Bearer <accessToken>`; no cookies are needed for business APIs.
- **Refresh, coordinated across tabs with the Web Locks API:**

  ```ts
  async function refreshSession(): Promise<Session> {
    return navigator.locks.request('alphaledger-refresh', async () => {
      // Another tab may have refreshed while this one waited; the cookie is shared, so just refresh once more:
      // the browser now sends the newest cookie, which is valid.
      const key = crypto.randomUUID().replaceAll('-', '');     // one key per attempt
      for (let attempt = 0; attempt < 2; attempt++) {          // one retry, same key, on a network error only
        try {
          return await post('/auth/refresh', {}, { headers: { 'X-Client': 'web', 'Idempotency-Key': key } });
        } catch (e) { if (!isNetworkError(e) || attempt === 1) throw e; }
      }
    });
  }
  ```

  Within a tab, concurrent 401s share one in-flight `refreshSession()` promise (single flight). Holding the lock
  across the request means two tabs never present the same cookie at once, so the server never sees a legitimate
  race.
- **On page load:** call `refreshSession()`; success restores the session, `401` shows the login page.
- **Logout:** `POST /auth/logout` with `X-Client: web`; drop the in-memory token; other tabs find out at their next
  refresh (`401`) and show the login page (optionally broadcast with `BroadcastChannel`).
- **Origin:** the app is served from the same origin as `/api` (nginx in Docker, Vite proxy in development), so cookies
  need no CORS credentials setup beyond the gateway's allowed origins.

## 7. Configuration and infrastructure

| Setting | Where | Default | Notes |
|---|---|---|---|
| `alphaledger.auth.session.cookie-secure` | auth-service (`AUTH_COOKIE_SECURE`) | `true` | `false` only in `infra/docker-compose.yml` (plain HTTP) |
| `alphaledger.auth.session.allowed-origins` | auth-service (`AUTH_ALLOWED_ORIGINS`) | `http://localhost:3000,http://localhost:5173` | Exact origins; set to the HTTPS origin when deployed |
| `alphaledger.auth.session.replay-window` | auth-service | `30s` | 0–120 s; 0 disables replay |
| `alphaledger.auth.session.cookie-path` | auth-service | `/api/v1/auth` | |
| `spring.cloud.gateway…allowed-origins` | api-gateway (`FRONTEND_ORIGINS`) | `http://localhost:3000,http://localhost:5173` | Renamed from `FRONTEND_ORIGIN`; credentials already allowed |
| Schema | auth-service `V3__refresh_token_rotation_chain.sql` | | Existing revoked rows get reason `LEGACY` |

The gateway passes `Cookie` and `Set-Cookie` through unchanged; `/api/v1/auth/login|refresh|logout` stay public paths.

## 8. Tests

| Test | Covers |
|---|---|
| `RefreshTokenServiceTest` (12) | Hash-only storage, rotation chain, reuse revokes all, replay with same key, repeated replays keep one active token, wrong key, after window, after successor used, zero window, logged-out token, expired/unknown/blank, window bounds |
| `AuthServiceTest` | Refresh for a disabled user revokes every token |
| `BrowserSessionTest` (4) | Exact cookie attributes in Secure and local modes, clearing, origin and `Sec-Fetch-Site` checks |
| `AuthCookieIT` (11, real Postgres) | Cookie flags and body without token; API mode unchanged and ignores cookies; rotation and reuse ending every session; logout; CSRF rejections (foreign/missing/`null`/duplicate Origin, cross-site fetch, form post) leave the token intact; expired cookie; 8 concurrent refreshes; 5 concurrent same-key retries; lost-response replay; malformed key; disabled user |
| `AuthApiIT` (10) | The existing body flow, unchanged |
| `GatewaySecurityTest` | Credentialed preflight from the dev server allowed, foreign origin refused |
| `scripts/smoke-test.sh` | Cookie login, refresh and cross-origin rejection through the real gateway |
