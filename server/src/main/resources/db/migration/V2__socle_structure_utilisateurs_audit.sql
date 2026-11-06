-- Phase 1 : structure, sections, utilisateurs, jetons, audit.

-- ---------------------------------------------------------------------------
-- Installation (premier lancement) : une seule ligne possible.
-- ---------------------------------------------------------------------------
CREATE TABLE installation (
    id             INTEGER PRIMARY KEY CHECK (id = 1),
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Secrets générés par le serveur (clé de signature JWT si non fournie en configuration).
CREATE TABLE server_secret (
    name       VARCHAR(50) PRIMARY KEY,
    value      BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Structure (la structure est elle-même le périmètre multi-structures).
-- ---------------------------------------------------------------------------
CREATE TABLE structure (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(150) NOT NULL,
    city              VARCHAR(100),
    ridet             VARCHAR(11),
    agrement_number   VARCHAR(50),
    agrement_date     DATE,
    capacity          INTEGER      NOT NULL CHECK (capacity > 0),
    invoice_footer    TEXT,
    logo              BYTEA,
    logo_content_type VARCHAR(50),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT structure_logo_type CHECK ((logo IS NULL) = (logo_content_type IS NULL))
);

-- Horaires d'ouverture : une ligne par jour ouvert (1 = lundi … 7 = dimanche).
CREATE TABLE structure_opening_hours (
    structure_id UUID    NOT NULL REFERENCES structure (id) ON DELETE CASCADE,
    day_of_week  INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    opens_at     TIME    NOT NULL,
    closes_at    TIME    NOT NULL,
    PRIMARY KEY (structure_id, day_of_week),
    CHECK (closes_at > opens_at)
);

CREATE TABLE section (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID        NOT NULL REFERENCES structure (id),
    name           VARCHAR(60) NOT NULL,
    min_age_months INTEGER     NOT NULL CHECK (min_age_months >= 0),
    max_age_months INTEGER CHECK (max_age_months IS NULL OR max_age_months > min_age_months),
    capacity       INTEGER     NOT NULL CHECK (capacity > 0),
    color          VARCHAR(7)  NOT NULL CHECK (color ~ '^#[0-9a-f]{6}$'),
    display_order  INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX section_structure_name_uk ON section (structure_id, lower(name));

-- ---------------------------------------------------------------------------
-- Utilisateurs : mot de passe (PC du bureau) et/ou PIN (tablette).
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id          UUID         NOT NULL REFERENCES structure (id),
    first_name            VARCHAR(80)  NOT NULL,
    last_name             VARCHAR(80)  NOT NULL,
    role                  VARCHAR(20)  NOT NULL
        CHECK (role IN ('DIRECTION', 'EDUCATRICE', 'CUISINE', 'COMPTABILITE')),
    login                 VARCHAR(60),
    password_hash         VARCHAR(255),
    pin_hash              VARCHAR(255),
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    failed_pin_attempts   INTEGER      NOT NULL DEFAULT 0,
    pin_locked_until      TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT app_user_password_needs_login CHECK (password_hash IS NULL OR login IS NOT NULL)
);
CREATE UNIQUE INDEX app_user_login_uk ON app_user (lower(login)) WHERE login IS NOT NULL;
CREATE INDEX app_user_structure_idx ON app_user (structure_id);

CREATE TABLE refresh_token (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID        NOT NULL REFERENCES structure (id),
    user_id      UUID        NOT NULL REFERENCES app_user (id),
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    family_id    UUID        NOT NULL,
    auth_method  VARCHAR(10) NOT NULL CHECK (auth_method IN ('PASSWORD', 'PIN')),
    issued_at    TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);

-- ---------------------------------------------------------------------------
-- Journaux (ajout seul).
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- NULL uniquement pour un échec de connexion avec un identifiant inconnu
    structure_id  UUID REFERENCES structure (id),
    occurred_at   TIMESTAMPTZ NOT NULL,
    actor_user_id UUID,
    actor_name    VARCHAR(170),
    action        VARCHAR(50) NOT NULL,
    target_type   VARCHAR(50),
    target_id     VARCHAR(64),
    details       JSONB,
    client_ip     VARCHAR(45)
);
CREATE INDEX audit_event_structure_time_idx ON audit_event (structure_id, occurred_at DESC);

-- Consultation d'un dossier enfant (fiche, santé, export…). child_id sans clé étrangère avant la phase 2.
CREATE TABLE child_record_access (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    structure_id UUID         NOT NULL REFERENCES structure (id),
    child_id     UUID         NOT NULL,
    user_id      UUID         NOT NULL REFERENCES app_user (id),
    user_name    VARCHAR(170) NOT NULL,
    accessed_at  TIMESTAMPTZ  NOT NULL,
    context      VARCHAR(30)  NOT NULL,
    client_ip    VARCHAR(45)
);
CREATE INDEX child_record_access_child_idx ON child_record_access (structure_id, child_id, accessed_at DESC);

CREATE FUNCTION forbid_journal_mutation() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'Le journal % est en ajout seul', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_journal_mutation();

CREATE TRIGGER child_record_access_append_only
    BEFORE UPDATE OR DELETE ON child_record_access
    FOR EACH ROW EXECUTE FUNCTION forbid_journal_mutation();
