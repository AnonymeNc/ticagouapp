-- Phase 8 : coordonnées et banque de la structure, règles de passage entre sections, sauvegardes, licence.

ALTER TABLE structure
    ADD COLUMN address               VARCHAR(300),
    ADD COLUMN postal_code           VARCHAR(10),
    ADD COLUMN phone                 VARCHAR(30),
    ADD COLUMN email                 VARCHAR(254),
    ADD COLUMN bank_holder           VARCHAR(150),
    ADD COLUMN bank_name             VARCHAR(100),
    ADD COLUMN iban                  VARCHAR(34),
    ADD COLUMN bic                   VARCHAR(11),
    ADD COLUMN print_rib             BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN passage_propose       BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN passage_first_of_month BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN passage_block_full    BOOLEAN NOT NULL DEFAULT TRUE;

-- Sauvegardes : réglage de l'installation (le serveur local sauvegarde toutes ses structures ensemble).
CREATE TABLE backup_settings (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    daily_time      TIME         NOT NULL DEFAULT '02:00',
    destination     VARCHAR(400),
    offsite         VARCHAR(400),
    keep_days       INTEGER      NOT NULL DEFAULT 30 CHECK (keep_days BETWEEN 1 AND 365),
    keep_months     INTEGER      NOT NULL DEFAULT 12 CHECK (keep_months BETWEEN 0 AND 120),
    key_handed_on   DATE,
    key_handed_by   VARCHAR(170),
    last_test_on    DATE,
    last_test_ok    BOOLEAN,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 0
);
INSERT INTO backup_settings (id) VALUES (1);

CREATE TABLE backup_run (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(15)  NOT NULL CHECK (kind IN ('AUTOMATIQUE', 'MANUELLE', 'TEST')),
    author      VARCHAR(170),
    started_at  TIMESTAMPTZ  NOT NULL,
    finished_at TIMESTAMPTZ,
    status      VARCHAR(10)  NOT NULL CHECK (status IN ('EN_COURS', 'REUSSIE', 'ECHEC')),
    size_bytes  BIGINT,
    file_name   VARCHAR(200),
    copies      INTEGER      NOT NULL DEFAULT 0,
    detail      VARCHAR(500)
);
CREATE INDEX backup_run_started_idx ON backup_run (started_at DESC);

-- Licence de l'installation : clé signée (Ed25519), vérifiée hors ligne à chaque lecture.
CREATE TABLE license (
    id           INTEGER PRIMARY KEY CHECK (id = 1),
    license_key  TEXT         NOT NULL,
    activated_at TIMESTAMPTZ  NOT NULL,
    activated_by VARCHAR(170) NOT NULL
);
