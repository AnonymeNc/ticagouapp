-- Portail parents (ADR 0010) : uniquement ce que chaque crèche partage, effaçable par structure et par famille.
-- Toute table porte structure_id ; les fichiers sont chiffrés par l'application (AES-256-GCM, clé par structure).

CREATE TABLE portal_structure (
    id           UUID PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    public_key   BYTEA        NOT NULL,
    last_seq     BIGINT       NOT NULL DEFAULT 0,
    phone        VARCHAR(40),
    payment_info VARCHAR(300),
    -- Heure limite pour signaler une absence sans repas facturé ; vide : la crèche ne le pratique pas.
    meal_absence_cutoff VARCHAR(5),
    activated_at TIMESTAMPTZ  NOT NULL,
    last_sync_at TIMESTAMPTZ,
    suspended_at TIMESTAMPTZ
);

-- Code remis par l'hébergeur à une crèche, à usage unique ; seule son empreinte est gardée.
CREATE TABLE activation_code (
    code_hash    VARCHAR(64) PRIMARY KEY,
    label        VARCHAR(150),
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    structure_id UUID
);

CREATE TABLE guardian (
    structure_id UUID         NOT NULL REFERENCES portal_structure (id) ON DELETE CASCADE,
    id           UUID         NOT NULL,
    family_id    UUID         NOT NULL,
    first_name   VARCHAR(100) NOT NULL,
    last_name    VARCHAR(100) NOT NULL,
    email        VARCHAR(150) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (structure_id, id)
);
CREATE INDEX guardian_email_idx ON guardian (lower(email));

CREATE TABLE child (
    structure_id  UUID         NOT NULL REFERENCES portal_structure (id) ON DELETE CASCADE,
    id            UUID         NOT NULL,
    family_id     UUID         NOT NULL,
    first_name    VARCHAR(100) NOT NULL,
    last_initial  VARCHAR(2),
    section       VARCHAR(60),
    photo         BYTEA,
    photo_type    VARCHAR(30),
    shared_health JSONB        NOT NULL DEFAULT '[]'::jsonb,
    updated_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (structure_id, id)
);
CREATE INDEX child_family_idx ON child (structure_id, family_id);

-- Éléments affichés aux parents : présences, transmissions, factures, documents, messages. Contenu JSON sans fichier ;
-- le fichier (photo, PDF) est chiffré à part. expires_on : effacement par la tâche de rétention.
CREATE TABLE item (
    structure_id UUID        NOT NULL REFERENCES portal_structure (id) ON DELETE CASCADE,
    id           UUID        NOT NULL,
    type         VARCHAR(20) NOT NULL,
    day          DATE,
    at           TIMESTAMPTZ,
    payload      JSONB       NOT NULL,
    file         BYTEA,
    file_type    VARCHAR(40),
    expires_on   DATE        NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (structure_id, id)
);
CREATE INDEX item_expiry_idx ON item (expires_on);

CREATE TABLE item_family (
    structure_id UUID NOT NULL,
    item_id      UUID NOT NULL,
    family_id    UUID NOT NULL,
    PRIMARY KEY (structure_id, item_id, family_id),
    FOREIGN KEY (structure_id, item_id) REFERENCES item (structure_id, id) ON DELETE CASCADE
);
CREATE INDEX item_family_idx ON item_family (structure_id, family_id);

CREATE TABLE item_child (
    structure_id UUID NOT NULL,
    item_id      UUID NOT NULL,
    child_id     UUID NOT NULL,
    PRIMARY KEY (structure_id, item_id, child_id),
    FOREIGN KEY (structure_id, item_id) REFERENCES item (structure_id, id) ON DELETE CASCADE
);
CREATE INDEX item_child_idx ON item_child (structure_id, child_id);

-- Ce que les parents envoient, en attente de lecture par le serveur de la crèche ; effacé à l'acquittement.
CREATE TABLE inbox (
    seq          BIGSERIAL PRIMARY KEY,
    structure_id UUID        NOT NULL REFERENCES portal_structure (id) ON DELETE CASCADE,
    id           UUID        NOT NULL UNIQUE,
    type         VARCHAR(12) NOT NULL CHECK (type IN ('ABSENCE', 'MESSAGE', 'SIGNATURE')),
    family_id    UUID        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX inbox_structure_idx ON inbox (structure_id, seq);

-- Connexion par lien magique et code : empreintes seulement, usage unique, 15 minutes.
CREATE TABLE login_request (
    id         UUID PRIMARY KEY,
    email      VARCHAR(150) NOT NULL,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    code_hash  VARCHAR(64)  NOT NULL,
    attempts   INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ
);
CREATE INDEX login_request_email_idx ON login_request (lower(email), created_at DESC);

CREATE TABLE parent_session (
    token_hash   VARCHAR(64) PRIMARY KEY,
    email        VARCHAR(150) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX parent_session_email_idx ON parent_session (lower(email));
