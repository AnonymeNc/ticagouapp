-- Phase 10 : transmissions du jour saisies sur la tablette, portail parents optionnel (ADR 0010).

-- ---------------------------------------------------------------------------
-- Transmissions : repas, sieste, change, activité, photo, mot. Une photo de groupe concerne plusieurs enfants.
-- ---------------------------------------------------------------------------
CREATE TABLE daily_entry (
    id              UUID PRIMARY KEY,
    structure_id    UUID         NOT NULL REFERENCES structure (id),
    day             DATE         NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    kind            VARCHAR(10)  NOT NULL CHECK (kind IN ('REPAS', 'SIESTE', 'CHANGE', 'ACTIVITE', 'PHOTO', 'MOT')),
    text            VARCHAR(500),
    details         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    photo_file_id   UUID REFERENCES stored_file (id),
    shared          BOOLEAN      NOT NULL DEFAULT TRUE,
    author_user_id  UUID REFERENCES app_user (id),
    author_name     VARCHAR(120) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX daily_entry_day_idx ON daily_entry (structure_id, day);

CREATE TABLE daily_entry_child (
    entry_id UUID NOT NULL REFERENCES daily_entry (id) ON DELETE CASCADE,
    child_id UUID NOT NULL REFERENCES child (id),
    PRIMARY KEY (entry_id, child_id)
);
CREATE INDEX daily_entry_child_idx ON daily_entry_child (child_id);

-- ---------------------------------------------------------------------------
-- Portail parents : réglages par structure, file d'envoi, accès des responsables.
-- ---------------------------------------------------------------------------
CREATE TABLE portal_settings (
    structure_id        UUID PRIMARY KEY REFERENCES structure (id),
    state               VARCHAR(16)  NOT NULL DEFAULT 'DESACTIVE'
                            CHECK (state IN ('DESACTIVE', 'ACTIF', 'PURGE_EN_COURS')),
    portal_url          VARCHAR(255),
    portal_structure_id UUID,
    share_entries       BOOLEAN      NOT NULL DEFAULT TRUE,
    share_photos        BOOLEAN      NOT NULL DEFAULT TRUE,
    share_invoices      BOOLEAN      NOT NULL DEFAULT TRUE,
    share_documents     BOOLEAN      NOT NULL DEFAULT TRUE,
    share_messages      BOOLEAN      NOT NULL DEFAULT TRUE,
    accept_absences     BOOLEAN      NOT NULL DEFAULT TRUE,
    payment_info        VARCHAR(300),
    meal_absence_cutoff VARCHAR(5) CHECK (meal_absence_cutoff ~ '^[0-2][0-9]:[0-5][0-9]$'),
    inbox_cursor        BIGINT       NOT NULL DEFAULT 0,
    last_success_at     TIMESTAMPTZ,
    last_attempt_at     TIMESTAMPTZ,
    last_error          VARCHAR(500),
    failures            INTEGER      NOT NULL DEFAULT 0,
    activated_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Éléments à envoyer : le contenu est construit à l'envoi depuis l'état courant (sauf les ordres d'effacement).
CREATE TABLE portal_outbox (
    seq          BIGSERIAL PRIMARY KEY,
    structure_id UUID        NOT NULL REFERENCES structure (id),
    type         VARCHAR(16) NOT NULL,
    entity_id    UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    sent_at      TIMESTAMPTZ
);
CREATE INDEX portal_outbox_pending_idx ON portal_outbox (structure_id, seq) WHERE sent_at IS NULL;
CREATE UNIQUE INDEX portal_outbox_pending_uk ON portal_outbox (structure_id, type, entity_id) WHERE sent_at IS NULL;

CREATE TABLE guardian_portal_access (
    guardian_id  UUID PRIMARY KEY REFERENCES guardian (id) ON DELETE CASCADE,
    structure_id UUID         NOT NULL REFERENCES structure (id),
    family_id    UUID         NOT NULL REFERENCES family (id),
    email        VARCHAR(150) NOT NULL,
    invited_at   TIMESTAMPTZ  NOT NULL,
    invited_by   VARCHAR(120) NOT NULL
);
CREATE INDEX guardian_portal_access_family_idx ON guardian_portal_access (family_id);

-- Information de santé partagée explicitement par la direction : texte figé au moment du partage.
CREATE TABLE health_share (
    id           UUID PRIMARY KEY,
    structure_id UUID         NOT NULL REFERENCES structure (id),
    child_id     UUID         NOT NULL REFERENCES child (id),
    label        VARCHAR(60)  NOT NULL,
    text         VARCHAR(300) NOT NULL,
    shared_by    VARCHAR(120) NOT NULL,
    shared_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX health_share_child_idx ON health_share (child_id);

-- Document proposé à la signature sur le portail : contrat (ou avenant) ou autorisation.
CREATE TABLE portal_document (
    id             UUID PRIMARY KEY,
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    family_id      UUID         NOT NULL REFERENCES family (id),
    child_id       UUID         NOT NULL REFERENCES child (id),
    kind           VARCHAR(12)  NOT NULL CHECK (kind IN ('CONTRAT', 'AUTORISATION')),
    contract_id    UUID REFERENCES contract (id),
    version_number INTEGER,
    consent_type   VARCHAR(20),
    title          VARCHAR(200) NOT NULL,
    file_id        UUID         NOT NULL REFERENCES stored_file (id),
    sha256         VARCHAR(64)  NOT NULL,
    status         VARCHAR(12)  NOT NULL CHECK (status IN ('A_SIGNER', 'SIGNE', 'ENREGISTRE', 'RETIRE')),
    proposed_by    VARCHAR(120) NOT NULL,
    proposed_at    TIMESTAMPTZ  NOT NULL,
    -- Preuve de la signature électronique simple, remontée du portail.
    signer_name    VARCHAR(120),
    signer_guardian_id UUID,
    signed_at      TIMESTAMPTZ,
    signer_ip      VARCHAR(45),
    signed_sha256  VARCHAR(64),
    signed_decision VARCHAR(8) CHECK (signed_decision IN ('ACCEPTE', 'REFUSE')),
    recorded_by    VARCHAR(120),
    recorded_at    TIMESTAMPTZ,
    portal_ref     UUID UNIQUE,
    CHECK ((kind = 'CONTRAT' AND contract_id IS NOT NULL AND version_number IS NOT NULL)
        OR (kind = 'AUTORISATION' AND consent_type IS NOT NULL))
);
CREATE INDEX portal_document_family_idx ON portal_document (family_id);

-- Absences signalées par les parents, à confirmer par la direction.
CREATE TABLE reported_absence (
    id             UUID PRIMARY KEY,
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    portal_ref     UUID         NOT NULL UNIQUE,
    family_id      UUID         NOT NULL REFERENCES family (id),
    child_id       UUID         NOT NULL REFERENCES child (id),
    guardian_name  VARCHAR(120),
    from_day       DATE         NOT NULL,
    to_day         DATE         NOT NULL CHECK (to_day >= from_day),
    reason         VARCHAR(300),
    reported_at    TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'SIGNALEE' CHECK (status IN ('SIGNALEE', 'CONFIRMEE', 'ECARTEE')),
    absence_id     UUID REFERENCES child_absence (id) ON DELETE SET NULL,
    handled_by     VARCHAR(120),
    handled_at     TIMESTAMPTZ
);
CREATE INDEX reported_absence_status_idx ON reported_absence (structure_id, status);

-- Messages échangés avec une famille, dans les deux sens.
CREATE TABLE family_message (
    id            UUID PRIMARY KEY,
    structure_id  UUID         NOT NULL REFERENCES structure (id),
    family_id     UUID         NOT NULL REFERENCES family (id),
    direction     VARCHAR(8)   NOT NULL CHECK (direction IN ('FAMILLE', 'EQUIPE')),
    author_name   VARCHAR(120) NOT NULL,
    author_user_id UUID REFERENCES app_user (id),
    body          VARCHAR(2000) NOT NULL,
    sent_at       TIMESTAMPTZ  NOT NULL,
    read_at       TIMESTAMPTZ,
    portal_ref    UUID UNIQUE
);
CREATE INDEX family_message_family_idx ON family_message (family_id, sent_at);
