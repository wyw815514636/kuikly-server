CREATE TABLE app.admin_accounts (
 id varchar(36) PRIMARY KEY, username varchar(64) NOT NULL UNIQUE,
 display_name varchar(80) NOT NULL, password_hash text NOT NULL,
 enabled boolean NOT NULL DEFAULT true, version bigint NOT NULL DEFAULT 0,
 must_change boolean NOT NULL DEFAULT true, mfa_secret text, last_totp_step bigint NOT NULL DEFAULT -1,
 reset_digest varchar(64), reset_expires bigint, created_at bigint NOT NULL
);
CREATE TABLE app.admin_roles (
 id varchar(36) PRIMARY KEY, name varchar(80) NOT NULL UNIQUE,
 system boolean NOT NULL DEFAULT false, version bigint NOT NULL DEFAULT 0
);
CREATE TABLE app.admin_role_permissions (role_id varchar(36) NOT NULL REFERENCES app.admin_roles(id), permission varchar(80) NOT NULL, PRIMARY KEY(role_id,permission));
CREATE TABLE app.admin_account_roles (account_id varchar(36) NOT NULL REFERENCES app.admin_accounts(id), role_id varchar(36) NOT NULL REFERENCES app.admin_roles(id), PRIMARY KEY(account_id,role_id));
CREATE TABLE app.admin_sessions (
 id varchar(36) PRIMARY KEY, digest varchar(64) NOT NULL UNIQUE,
 account_id varchar(36) NOT NULL REFERENCES app.admin_accounts(id), csrf varchar(64) NOT NULL,
 created_at bigint NOT NULL, last_active bigint NOT NULL, expires_at bigint NOT NULL,
 recent_at bigint NOT NULL DEFAULT 0, revoked boolean NOT NULL DEFAULT false, device varchar(160) NOT NULL
);
CREATE INDEX admin_sessions_account_idx ON app.admin_sessions(account_id);
CREATE TABLE app.admin_challenges (
 digest varchar(64) PRIMARY KEY, account_id varchar(36) NOT NULL REFERENCES app.admin_accounts(id),
 stage varchar(32) NOT NULL, expires_at bigint NOT NULL, secret text, attempts integer NOT NULL DEFAULT 0
);
CREATE TABLE app.admin_mfa_recovery (account_id varchar(36) NOT NULL REFERENCES app.admin_accounts(id), digest varchar(64) NOT NULL, PRIMARY KEY(account_id,digest));
CREATE TABLE app.admin_rate_limits (key varchar(64) PRIMARY KEY, bucket bigint NOT NULL, attempts integer NOT NULL);
CREATE TABLE app.admin_idempotency (
 account_id varchar(36) NOT NULL REFERENCES app.admin_accounts(id), operation varchar(80) NOT NULL,
 key varchar(36) NOT NULL, fingerprint varchar(64) NOT NULL, ciphertext text,
 expires_at bigint NOT NULL, retain_until bigint NOT NULL, PRIMARY KEY(account_id,operation,key)
);
CREATE TABLE app.admin_audit_events (
 id varchar(36) PRIMARY KEY, actor_id varchar(36), action varchar(80) NOT NULL,
 target_id varchar(128), result varchar(32) NOT NULL, trace_id varchar(128) NOT NULL,
 occurred_at bigint NOT NULL, metadata text NOT NULL DEFAULT '{}'
);
CREATE INDEX admin_audit_time_idx ON app.admin_audit_events(occurred_at DESC,id DESC);
CREATE INDEX admin_audit_actor_idx ON app.admin_audit_events(actor_id,occurred_at DESC);
INSERT INTO app.admin_roles (id,name,system) VALUES ('00000000-0000-0000-0000-000000000001','超级管理员',true);
INSERT INTO app.admin_role_permissions(role_id,permission)
 SELECT '00000000-0000-0000-0000-000000000001',p FROM unnest(ARRAY[
 'admin:read','admin:create','admin:update','admin:status','admin:reset-password','admin:revoke-session','admin:assign-role',
 'role:read','role:create','role:update','role:delete','audit:read']) p;
