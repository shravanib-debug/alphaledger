-- Refresh-token rotation chain (M0, docs/auth-session.md).
-- Records why a token stopped being valid and which token replaced it, so reuse detection and the narrow
-- lost-response replay can tell "just rotated by this same request" apart from "presented again by someone else".

ALTER TABLE refresh_token
    ADD COLUMN revoked_reason    VARCHAR(20),
    ADD COLUMN revoked_at        TIMESTAMP WITH TIME ZONE,
    -- The token issued when this one was rotated (or, after a replay, the latest one issued for that rotation).
    ADD COLUMN replaced_by       BIGINT REFERENCES refresh_token (id),
    -- SHA-256 of the client's Idempotency-Key for the rotation; only a retry carrying the same key can replay it.
    ADD COLUMN rotation_key_hash VARCHAR(64);

-- Tokens revoked before this migration have no recorded reason.
UPDATE refresh_token SET revoked_reason = 'LEGACY', revoked_at = created_at WHERE revoked;

ALTER TABLE refresh_token
    ADD CONSTRAINT ck_refresh_token_reason
        CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'REPLAY_SUPERSEDED', 'LEGACY')),
    ADD CONSTRAINT ck_refresh_token_revoked_has_reason
        CHECK (revoked = (revoked_reason IS NOT NULL)),
    ADD CONSTRAINT ck_refresh_token_rotated_has_successor
        CHECK (revoked_reason <> 'ROTATED' OR replaced_by IS NOT NULL);
