-- Phase 4 : pointage des enfants et du personnel, règles d'encadrement, tablettes appairées.

-- ---------------------------------------------------------------------------
-- Règles d'encadrement : enfants par adulte présent, par tranche d'âge, datées.
-- ---------------------------------------------------------------------------
CREATE TABLE ratio_rule (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id       UUID        NOT NULL REFERENCES structure (id),
    label              VARCHAR(80) NOT NULL,
    min_age_months     INTEGER     NOT NULL CHECK (min_age_months >= 0),
    max_age_months     INTEGER     CHECK (max_age_months IS NULL OR max_age_months > min_age_months),
    children_per_adult INTEGER     NOT NULL CHECK (children_per_adult BETWEEN 1 AND 30),
    valid_from         DATE        NOT NULL,
    valid_to           DATE        CHECK (valid_to IS NULL OR valid_to >= valid_from),
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX ratio_rule_structure_idx ON ratio_rule (structure_id);

INSERT INTO ratio_rule (structure_id, label, min_age_months, max_age_months, children_per_adult, valid_from,
                        created_at, updated_at)
SELECT id, 'Enfants qui ne marchent pas', 0, 18, 5, (created_at AT TIME ZONE 'Pacific/Noumea')::date, now(), now()
FROM structure;
INSERT INTO ratio_rule (structure_id, label, min_age_months, max_age_months, children_per_adult, valid_from,
                        created_at, updated_at)
SELECT id, 'Enfants qui marchent', 18, NULL, 8, (created_at AT TIME ZONE 'Pacific/Noumea')::date, now(), now()
FROM structure;

-- Option « Prévenir la direction sur le PC du bureau en cas de dépassement ».
ALTER TABLE structure ADD COLUMN ratio_alert_desktop BOOLEAN NOT NULL DEFAULT TRUE;

-- Personnel : fonction, section habituelle, prise en compte dans le taux d'encadrement.
ALTER TABLE app_user
    ADD COLUMN job_title       VARCHAR(80),
    ADD COLUMN home_section_id UUID REFERENCES section (id) ON DELETE SET NULL,
    ADD COLUMN counts_in_ratio BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE app_user SET counts_in_ratio = (role = 'EDUCATRICE');

-- ---------------------------------------------------------------------------
-- Tablettes appairées : secret haché, code d'appairage à usage unique, blocage du PIN seul.
-- ---------------------------------------------------------------------------
CREATE TABLE device (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id        UUID         NOT NULL REFERENCES structure (id),
    name                VARCHAR(60)  NOT NULL,
    token_hash          VARCHAR(64) UNIQUE,
    pairing_code_hash   VARCHAR(64),
    pairing_expires_at  TIMESTAMPTZ,
    paired_at           TIMESTAMPTZ,
    created_by          VARCHAR(170) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    last_seen_at        TIMESTAMPTZ,
    user_agent          VARCHAR(200),
    revoked_at          TIMESTAMPTZ,
    failed_pin_attempts INTEGER      NOT NULL DEFAULT 0,
    pin_locked_until    TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX device_structure_idx ON device (structure_id);
CREATE INDEX device_pairing_idx ON device (pairing_code_hash) WHERE pairing_code_hash IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Pointage des enfants. Un seul pointage ouvert par enfant et par jour ; un enfant peut venir deux fois.
-- ---------------------------------------------------------------------------
CREATE TABLE child_attendance (
    id                    UUID PRIMARY KEY,
    structure_id          UUID         NOT NULL REFERENCES structure (id),
    child_id              UUID         NOT NULL REFERENCES child (id),
    day                   DATE         NOT NULL,
    section_id            UUID REFERENCES section (id) ON DELETE SET NULL,
    arrived_at            TIMESTAMPTZ  NOT NULL,
    departed_at           TIMESTAMPTZ CHECK (departed_at IS NULL OR departed_at >= arrived_at),
    dropped_off_by        VARCHAR(120),
    picked_up_by_kind     VARCHAR(12) CHECK (picked_up_by_kind IN ('RESPONSABLE', 'AUTORISEE', 'AUTRE')),
    picked_up_by_id       UUID,
    picked_up_by_name     VARCHAR(170),
    unplanned             BOOLEAN      NOT NULL DEFAULT FALSE,
    arrival_recorded_by   VARCHAR(170) NOT NULL,
    departure_recorded_by VARCHAR(170),
    source                VARCHAR(10)  NOT NULL CHECK (source IN ('TABLETTE', 'BUREAU')),
    needs_review          BOOLEAN      NOT NULL DEFAULT FALSE,
    review_reason         VARCHAR(300),
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT child_attendance_pickup CHECK (departed_at IS NULL OR picked_up_by_kind IS NOT NULL)
);
CREATE UNIQUE INDEX child_attendance_open_uk ON child_attendance (child_id, day) WHERE departed_at IS NULL;
CREATE INDEX child_attendance_day_idx ON child_attendance (structure_id, day);

-- Consignes du parent à l'arrivée (identifiant fourni par la tablette).
CREATE TABLE child_instruction (
    id                 UUID PRIMARY KEY,
    structure_id       UUID         NOT NULL REFERENCES structure (id),
    attendance_id      UUID         NOT NULL REFERENCES child_attendance (id) ON DELETE CASCADE,
    kind               VARCHAR(20)  NOT NULL
        CHECK (kind IN ('A_MAL_DORMI', 'PEU_MANGE', 'MEDICAMENT', 'NEZ_QUI_COULE', 'SIESTE_AVANCER', 'AUTRE')),
    details            VARCHAR(300),
    medication_at      TIME,
    medication_dose    VARCHAR(150),
    prescription_given BOOLEAN      NOT NULL DEFAULT FALSE,
    given_at           TIMESTAMPTZ,
    given_by           VARCHAR(170),
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT child_instruction_medication CHECK (kind <> 'MEDICAMENT' OR medication_at IS NOT NULL)
);
CREATE INDEX child_instruction_attendance_idx ON child_instruction (attendance_id);

-- Départ avec une personne absente de la liste : validation de la direction.
CREATE TABLE departure_override (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id    UUID         NOT NULL REFERENCES structure (id),
    attendance_id   UUID         NOT NULL UNIQUE REFERENCES child_attendance (id) ON DELETE CASCADE,
    person_name     VARCHAR(170) NOT NULL,
    relationship    VARCHAR(120) NOT NULL,
    id_checked      BOOLEAN      NOT NULL,
    validated_by_id UUID REFERENCES app_user (id),
    validated_by    VARCHAR(170) NOT NULL,
    validated_at    TIMESTAMPTZ  NOT NULL,
    pin_verified    BOOLEAN      NOT NULL,
    reason          VARCHAR(300)
);

-- ---------------------------------------------------------------------------
-- Pointage du personnel.
-- ---------------------------------------------------------------------------
CREATE TABLE staff_attendance (
    id            UUID PRIMARY KEY,
    structure_id  UUID        NOT NULL REFERENCES structure (id),
    user_id       UUID        NOT NULL REFERENCES app_user (id),
    day           DATE        NOT NULL,
    section_id    UUID REFERENCES section (id) ON DELETE SET NULL,
    arrived_at    TIMESTAMPTZ NOT NULL,
    departed_at   TIMESTAMPTZ CHECK (departed_at IS NULL OR departed_at >= arrived_at),
    source        VARCHAR(15) NOT NULL CHECK (source IN ('PIN', 'PIN_HORS_LIGNE', 'BUREAU')),
    pin_verified  BOOLEAN     NOT NULL DEFAULT TRUE,
    needs_review  BOOLEAN     NOT NULL DEFAULT FALSE,
    review_reason VARCHAR(300),
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX staff_attendance_open_uk ON staff_attendance (user_id, day) WHERE departed_at IS NULL;
CREATE INDEX staff_attendance_day_idx ON staff_attendance (structure_id, day);

-- ---------------------------------------------------------------------------
-- Opérations reçues des tablettes : idempotence de la resynchronisation. Le contenu n'est pas
-- conservé (il peut contenir un PIN), seulement le résultat.
-- ---------------------------------------------------------------------------
CREATE TABLE kiosk_operation (
    client_id    UUID PRIMARY KEY,
    structure_id UUID        NOT NULL REFERENCES structure (id),
    device_id    UUID        NOT NULL REFERENCES device (id),
    type         VARCHAR(30) NOT NULL,
    user_id      UUID,
    child_id     UUID,
    occurred_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL,
    outcome      VARCHAR(12) NOT NULL CHECK (outcome IN ('APPLIQUEE', 'DEJA_RECUE', 'REFUSEE')),
    code         VARCHAR(40),
    message      VARCHAR(300)
);
CREATE INDEX kiosk_operation_refused_idx ON kiosk_operation (structure_id, received_at) WHERE outcome = 'REFUSEE';

-- ---------------------------------------------------------------------------
-- Corrections de pointage par la direction : journal en ajout seul.
-- Pas de clé étrangère : la trace survit à la purge des données d'une famille.
-- ---------------------------------------------------------------------------
CREATE TABLE attendance_correction (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id  UUID         NOT NULL REFERENCES structure (id),
    target        VARCHAR(10)  NOT NULL CHECK (target IN ('ENFANT', 'PERSONNEL')),
    attendance_id UUID         NOT NULL,
    field         VARCHAR(20)  NOT NULL,
    old_value     VARCHAR(100),
    new_value     VARCHAR(100),
    reason        VARCHAR(300) NOT NULL,
    author_id     UUID,
    author        VARCHAR(170) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX attendance_correction_target_idx ON attendance_correction (attendance_id);

CREATE TRIGGER attendance_correction_append_only
    BEFORE UPDATE OR DELETE ON attendance_correction
    FOR EACH ROW EXECUTE FUNCTION forbid_journal_mutation();

-- ---------------------------------------------------------------------------
-- Dépassements du taux d'encadrement (section vide = toute la structure).
-- ---------------------------------------------------------------------------
CREATE TABLE ratio_breach (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id  UUID        NOT NULL REFERENCES structure (id),
    section_id    UUID,
    section_name  VARCHAR(60),
    started_at    TIMESTAMPTZ NOT NULL,
    ended_at      TIMESTAMPTZ,
    max_children  INTEGER     NOT NULL,
    min_adults    INTEGER     NOT NULL,
    max_required  INTEGER     NOT NULL
);
CREATE UNIQUE INDEX ratio_breach_open_uk ON ratio_breach (structure_id, coalesce(section_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE ended_at IS NULL;
