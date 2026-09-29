CREATE TABLE app.users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (username ~ '^[a-z0-9][a-z0-9_.-]{2,63}$')
);

CREATE TABLE app.auth_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES app.users(id),
    expires_at BIGINT NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX auth_sessions_user_idx ON app.auth_sessions(user_id);
CREATE INDEX auth_sessions_expiry_idx ON app.auth_sessions(expires_at);

CREATE TABLE app.refresh_tokens (
    digest VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES app.auth_sessions(id) ON DELETE CASCADE,
    consumed_at BIGINT,
    request_id VARCHAR(36),
    successor_digest VARCHAR(64),
    retry_until BIGINT,
    retry_ciphertext TEXT,
    CHECK ((consumed_at IS NULL AND request_id IS NULL AND successor_digest IS NULL)
        OR (consumed_at IS NOT NULL AND request_id IS NOT NULL AND successor_digest IS NOT NULL))
);
CREATE INDEX refresh_tokens_session_idx ON app.refresh_tokens(session_id);
CREATE UNIQUE INDEX refresh_tokens_active_idx ON app.refresh_tokens(session_id) WHERE consumed_at IS NULL;
CREATE INDEX refresh_tokens_retry_expiry_idx ON app.refresh_tokens(retry_until) WHERE retry_ciphertext IS NOT NULL;

CREATE TABLE app.login_attempts (
    key VARCHAR(64) PRIMARY KEY,
    window_started_at BIGINT NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts >= 0)
);
CREATE INDEX login_attempts_expiry_idx ON app.login_attempts(window_started_at);
