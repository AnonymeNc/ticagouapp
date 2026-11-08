-- Console de l'éditeur (ADR 0017) : comptes et journal. Une seule entreprise : pas de structure_id.

-- Compte nominatif ; mot de passe BCrypt ; secret TOTP chiffré (AES-256-GCM, CONSOLE_SECRET_KEY).
CREATE TABLE operator (
    id              UUID PRIMARY KEY,
    login           VARCHAR(60)  NOT NULL,
    display_name    VARCHAR(120) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    totp_secret     VARCHAR(200),
    totp_enabled_at TIMESTAMPTZ,
    totp_last_step  BIGINT,
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    last_login_at   TIMESTAMPTZ,
    disabled_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX operator_login_idx ON operator (lower(login));

-- Toute connexion et toute écriture. operator_login est copié : le journal survit à la suppression d'un compte.
CREATE TABLE audit_event (
    id             BIGSERIAL PRIMARY KEY,
    at             TIMESTAMPTZ  NOT NULL,
    operator_id    UUID,
    operator_login VARCHAR(60),
    action         VARCHAR(40)  NOT NULL,
    target         VARCHAR(200),
    detail         JSONB,
    ip             VARCHAR(64)
);
CREATE INDEX audit_event_at_idx ON audit_event (at DESC);
