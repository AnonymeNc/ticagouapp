-- Phase 7 : fiches du personnel, qualifications, formations, planning hebdomadaire, absences, feuilles d'heures.
-- Une fiche par compte (app_user) ; durées en minutes.

CREATE TABLE staff_profile (
    user_id           UUID PRIMARY KEY REFERENCES app_user (id),
    structure_id      UUID         NOT NULL REFERENCES structure (id),
    birth_date        DATE,
    phone             VARCHAR(30),
    email             VARCHAR(254),
    address           VARCHAR(300),
    cafat_number      VARCHAR(30),
    emergency_contact VARCHAR(200),
    diploma           VARCHAR(120),
    contract_type     VARCHAR(20)  NOT NULL DEFAULT 'CDI'
        CHECK (contract_type IN ('CDI', 'CDD', 'REMPLACEMENT', 'APPRENTISSAGE', 'VACATAIRE')),
    -- NULL : rémunéré à l'heure, sans durée hebdomadaire fixe.
    weekly_minutes    INTEGER CHECK (weekly_minutes IS NULL OR weekly_minutes BETWEEN 0 AND 3600),
    hired_on          DATE,
    ends_on           DATE CHECK (ends_on IS NULL OR hired_on IS NULL OR ends_on >= hired_on),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0
);

INSERT INTO staff_profile (user_id, structure_id, weekly_minutes, created_at, updated_at)
SELECT id, structure_id, 39 * 60, now(), now() FROM app_user;

CREATE TABLE staff_qualification (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    user_id      UUID         NOT NULL REFERENCES app_user (id),
    kind         VARCHAR(15)  NOT NULL CHECK (kind IN ('DIPLOME', 'SST', 'HACCP', 'APTITUDE', 'AUTRE')),
    label        VARCHAR(160) NOT NULL,
    obtained_on  DATE,
    valid_until  DATE CHECK (valid_until IS NULL OR obtained_on IS NULL OR valid_until >= obtained_on),
    file_id      UUID REFERENCES stored_file (id),
    file_name    VARCHAR(200),
    verified     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX staff_qualification_user_idx ON staff_qualification (user_id);

CREATE TABLE staff_training (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    user_id      UUID         NOT NULL REFERENCES app_user (id),
    label        VARCHAR(200) NOT NULL,
    minutes      INTEGER      NOT NULL CHECK (minutes > 0),
    starts_on    DATE         NOT NULL,
    ends_on      DATE         NOT NULL CHECK (ends_on >= starts_on),
    organization VARCHAR(160),
    done         BOOLEAN      NOT NULL DEFAULT FALSE,
    file_id      UUID REFERENCES stored_file (id),
    file_name    VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX staff_training_user_idx ON staff_training (user_id);

-- Semaine type, datée : la version en vigueur un jour donné est la plus récente dont effective_from <= jour.
CREATE TABLE staff_schedule (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID        NOT NULL REFERENCES structure (id),
    user_id        UUID        NOT NULL REFERENCES app_user (id),
    effective_from DATE        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT staff_schedule_uk UNIQUE (user_id, effective_from)
);

CREATE TABLE staff_schedule_day (
    schedule_id   UUID    NOT NULL REFERENCES staff_schedule (id) ON DELETE CASCADE,
    day_of_week   INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    starts_at     TIME    NOT NULL,
    ends_at       TIME    NOT NULL CHECK (ends_at > starts_at),
    break_minutes INTEGER NOT NULL DEFAULT 0 CHECK (break_minutes BETWEEN 0 AND 240),
    section_id    UUID REFERENCES section (id),
    PRIMARY KEY (schedule_id, day_of_week)
);

-- Exception d'un jour : autre horaire, autre section, ou jour non travaillé (starts_at NULL).
CREATE TABLE staff_shift_override (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id  UUID         NOT NULL REFERENCES structure (id),
    user_id       UUID         NOT NULL REFERENCES app_user (id),
    day           DATE         NOT NULL,
    starts_at     TIME,
    ends_at       TIME,
    break_minutes INTEGER      NOT NULL DEFAULT 0 CHECK (break_minutes BETWEEN 0 AND 240),
    section_id    UUID REFERENCES section (id),
    note          VARCHAR(200),
    author        VARCHAR(170) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_shift_override_uk UNIQUE (user_id, day),
    CONSTRAINT staff_shift_override_times CHECK ((starts_at IS NULL) = (ends_at IS NULL)
        AND (starts_at IS NULL OR ends_at > starts_at))
);

CREATE TABLE staff_absence (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    user_id      UUID         NOT NULL REFERENCES app_user (id),
    kind         VARCHAR(15)  NOT NULL CHECK (kind IN ('CONGES', 'MALADIE', 'FORMATION', 'RECUPERATION', 'AUTRE')),
    from_day     DATE         NOT NULL,
    to_day       DATE         NOT NULL CHECK (to_day >= from_day),
    half_day     BOOLEAN      NOT NULL DEFAULT FALSE,
    note         VARCHAR(200),
    author       VARCHAR(170) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_absence_half CHECK (NOT half_day OR from_day = to_day)
);
CREATE INDEX staff_absence_user_idx ON staff_absence (user_id, from_day);

-- Paramètres du module, datés.
CREATE TABLE staff_settings (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id          UUID        NOT NULL REFERENCES structure (id),
    valid_from            DATE        NOT NULL,
    alert_days            INTEGER     NOT NULL CHECK (alert_days BETWEEN 1 AND 180),
    default_break_minutes INTEGER     NOT NULL CHECK (default_break_minutes BETWEEN 0 AND 240),
    reference_weekly_minutes INTEGER  NOT NULL CHECK (reference_weekly_minutes BETWEEN 60 AND 3600),
    created_at            TIMESTAMPTZ NOT NULL,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT staff_settings_uk UNIQUE (structure_id, valid_from)
);

INSERT INTO staff_settings (structure_id, valid_from, alert_days, default_break_minutes, reference_weekly_minutes, created_at)
SELECT id, DATE '2024-01-01', 30, 30, 39 * 60, now() FROM structure;

-- Mois validé par la direction : la feuille d'heures exportée pour la paie est figée.
CREATE TABLE timesheet_month (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    month        DATE         NOT NULL CHECK (extract(day FROM month) = 1),
    validated_at TIMESTAMPTZ  NOT NULL,
    validated_by VARCHAR(170) NOT NULL,
    -- Feuille d'heures figée au moment de la validation (JSON).
    snapshot     TEXT         NOT NULL,
    CONSTRAINT timesheet_month_uk UNIQUE (structure_id, month)
);
