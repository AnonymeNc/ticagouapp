-- Phase 2 : enfants, familles, personnes autorisées, santé, autorisations, documents.

-- Compteurs par structure et par année (numéros de dossier, puis factures).
CREATE TABLE structure_counter (
    structure_id UUID        NOT NULL REFERENCES structure (id),
    name         VARCHAR(30) NOT NULL,
    year         INTEGER     NOT NULL,
    value        INTEGER     NOT NULL,
    PRIMARY KEY (structure_id, name, year)
);

-- Fichier chiffré sur disque (AES-256-GCM) ; la base ne garde que les métadonnées.
CREATE TABLE stored_file (
    id           UUID PRIMARY KEY,
    structure_id UUID         NOT NULL REFERENCES structure (id),
    storage_path VARCHAR(255) NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL CHECK (size_bytes >= 0),
    sha256       VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);

-- ---------------------------------------------------------------------------
-- Familles
-- ---------------------------------------------------------------------------
CREATE TABLE family (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    label        VARCHAR(120) NOT NULL,
    address      VARCHAR(200),
    postal_code  VARCHAR(10),
    city         VARCHAR(100),
    situation    VARCHAR(500),
    notes        VARCHAR(2000),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX family_structure_idx ON family (structure_id);

-- Responsables légaux et autres adultes du foyer ; le payeur reçoit les factures.
CREATE TABLE guardian (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    family_id      UUID         NOT NULL REFERENCES family (id),
    first_name     VARCHAR(80)  NOT NULL,
    last_name      VARCHAR(80)  NOT NULL,
    relationship   VARCHAR(60)  NOT NULL,
    legal_guardian BOOLEAN      NOT NULL,
    payer          BOOLEAN      NOT NULL,
    mobile_phone   VARCHAR(30),
    work_phone     VARCHAR(30),
    email          VARCHAR(150),
    employer       VARCHAR(150),
    display_order  INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX guardian_family_idx ON guardian (family_id);

-- ---------------------------------------------------------------------------
-- Enfants
-- ---------------------------------------------------------------------------
CREATE TABLE child (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    family_id      UUID         NOT NULL REFERENCES family (id),
    file_number    VARCHAR(20)  NOT NULL,
    first_name     VARCHAR(80)  NOT NULL,
    last_name      VARCHAR(80)  NOT NULL,
    birth_date     DATE         NOT NULL,
    section_id     UUID REFERENCES section (id),
    entry_date     DATE,
    exit_date      DATE,
    home_languages VARCHAR(200),
    photo_file_id  UUID REFERENCES stored_file (id),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT child_file_number_uk UNIQUE (structure_id, file_number),
    CHECK (entry_date IS NULL OR entry_date >= birth_date),
    CHECK (exit_date IS NULL OR entry_date IS NULL OR exit_date >= entry_date)
);
CREATE INDEX child_structure_idx ON child (structure_id);
CREATE INDEX child_family_idx ON child (family_id);

-- Personnes autorisées à venir chercher l'enfant (en plus des responsables légaux).
CREATE TABLE authorized_person (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id     UUID         NOT NULL REFERENCES structure (id),
    child_id         UUID         NOT NULL REFERENCES child (id),
    first_name       VARCHAR(80)  NOT NULL,
    last_name        VARCHAR(80)  NOT NULL,
    relationship     VARCHAR(60)  NOT NULL,
    phone            VARCHAR(30),
    id_verified_on   DATE,
    id_verified_by   VARCHAR(170),
    photo_file_id    UUID REFERENCES stored_file (id),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX authorized_person_child_idx ON authorized_person (child_id);

-- Documents joints (contenu chiffré dans stored_file).
CREATE TABLE child_document (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    child_id     UUID         NOT NULL REFERENCES child (id),
    file_id      UUID         NOT NULL REFERENCES stored_file (id),
    name         VARCHAR(150) NOT NULL,
    file_name    VARCHAR(200) NOT NULL,
    category     VARCHAR(20)  NOT NULL
        CHECK (category IN ('CONTRAT', 'SANTE', 'AUTORISATIONS', 'FAMILLE', 'AUTRE')),
    valid_until  DATE,
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(170) NOT NULL
);
CREATE INDEX child_document_child_idx ON child_document (child_id);

-- ---------------------------------------------------------------------------
-- Santé
-- ---------------------------------------------------------------------------
CREATE TABLE health_info (
    child_id               UUID PRIMARY KEY REFERENCES child (id),
    structure_id           UUID          NOT NULL REFERENCES structure (id),
    diet                   VARCHAR(100),
    diet_details           VARCHAR(500),
    pai_active             BOOLEAN       NOT NULL DEFAULT FALSE,
    pai_reason             VARCHAR(200),
    pai_valid_from         DATE,
    pai_valid_until        DATE,
    pai_kit_location       VARCHAR(200),
    pai_kit_details        VARCHAR(500),
    pai_document_id        UUID REFERENCES child_document (id),
    emergency_protocol     VARCHAR(500),
    doctor_name            VARCHAR(120),
    doctor_phone           VARCHAR(30),
    doctor_address         VARCHAR(200),
    instructions           VARCHAR(2000),
    vaccination_checked_on DATE,
    updated_at             TIMESTAMPTZ   NOT NULL,
    version                BIGINT        NOT NULL DEFAULT 0,
    CHECK (pai_valid_until IS NULL OR pai_valid_from IS NULL OR pai_valid_until >= pai_valid_from)
);

CREATE TABLE allergy (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    child_id     UUID         NOT NULL REFERENCES child (id),
    allergen     VARCHAR(100) NOT NULL,
    reaction     VARCHAR(300),
    severity     VARCHAR(10)  NOT NULL CHECK (severity IN ('LEGERE', 'MODEREE', 'SEVERE')),
    display_order INTEGER     NOT NULL DEFAULT 0
);
CREATE INDEX allergy_child_idx ON allergy (child_id);

CREATE TABLE vaccination (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    child_id       UUID         NOT NULL REFERENCES child (id),
    vaccine        VARCHAR(100) NOT NULL,
    last_dose_on   DATE,
    display_order  INTEGER      NOT NULL DEFAULT 0
);
CREATE INDEX vaccination_child_idx ON vaccination (child_id);

-- Autorisations parentales : photos, sorties, soins d'urgence.
CREATE TABLE consent (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    child_id     UUID         NOT NULL REFERENCES child (id),
    type         VARCHAR(20)  NOT NULL CHECK (type IN ('PHOTOS', 'SORTIES', 'SOINS_URGENCE')),
    granted      BOOLEAN      NOT NULL,
    details      VARCHAR(500),
    signed_on    DATE,
    signed_by    VARCHAR(170),
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT consent_child_type_uk UNIQUE (child_id, type)
);
