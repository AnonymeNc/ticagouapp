-- Phase 3 : barèmes, demandes d'inscription, contrats d'accueil et avenants.
-- Les tables de détail (tranches, jours, séances) appartiennent à leur parent et héritent de sa structure.

-- ---------------------------------------------------------------------------
-- Barèmes tarifaires, datés
-- ---------------------------------------------------------------------------
CREATE TABLE tariff_schedule (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id     UUID         NOT NULL REFERENCES structure (id),
    label            VARCHAR(80)  NOT NULL,
    -- QUOTIENT_FAMILIAL : bornes en XPF ; HEURES_SEMAINE : bornes en minutes par semaine
    criterion        VARCHAR(20)  NOT NULL CHECK (criterion IN ('QUOTIENT_FAMILIAL', 'HEURES_SEMAINE')),
    weeks_per_year   INTEGER      NOT NULL CHECK (weeks_per_year BETWEEN 1 AND 52),
    registration_fee BIGINT       NOT NULL CHECK (registration_fee >= 0),
    valid_from       DATE         NOT NULL,
    valid_to         DATE,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX tariff_schedule_structure_idx ON tariff_schedule (structure_id, valid_from);

-- Tranche : s'applique à partir de sa borne inférieure (incluse) jusqu'à la borne suivante.
CREATE TABLE tariff_band (
    schedule_id UUID   NOT NULL REFERENCES tariff_schedule (id) ON DELETE CASCADE,
    lower_bound BIGINT NOT NULL CHECK (lower_bound >= 0),
    hourly_rate BIGINT NOT NULL CHECK (hourly_rate > 0),
    PRIMARY KEY (schedule_id, lower_bound)
);

-- ---------------------------------------------------------------------------
-- Demandes d'inscription
-- ---------------------------------------------------------------------------
CREATE TABLE enrollment_criterion (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id  UUID         NOT NULL REFERENCES structure (id),
    label         VARCHAR(120) NOT NULL,
    points        INTEGER      NOT NULL CHECK (points BETWEEN 0 AND 20),
    valid_from    DATE         NOT NULL,
    valid_to      DATE,
    display_order INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX enrollment_criterion_structure_idx ON enrollment_criterion (structure_id);

CREATE TABLE enrollment_request (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id        UUID         NOT NULL REFERENCES structure (id),
    received_on         DATE         NOT NULL,
    child_first_name    VARCHAR(80)  NOT NULL,
    child_last_name     VARCHAR(80)  NOT NULL,
    birth_date          DATE,
    expected_birth_date DATE,
    section_id          UUID REFERENCES section (id) ON DELETE SET NULL,
    desired_start       DATE         NOT NULL,
    care_type           VARCHAR(20)  NOT NULL CHECK (care_type IN ('REGULIER', 'OCCASIONNEL')),
    arrives_at          TIME,
    leaves_at           TIME,
    guardian_first_name VARCHAR(80)  NOT NULL,
    guardian_last_name  VARCHAR(80)  NOT NULL,
    relationship        VARCHAR(60)  NOT NULL,
    phone               VARCHAR(30),
    email               VARCHAR(150),
    family_id           UUID REFERENCES family (id),
    stage               VARCHAR(20)  NOT NULL CHECK (stage IN
                            ('NOUVELLE', 'A_RECONTACTER', 'VISITE_PREVUE', 'PLACE_PROPOSEE', 'SIGNEE', 'REFUSEE')),
    stage_changed_on    DATE         NOT NULL,
    follow_up_on        DATE,
    visit_at            TIMESTAMP,
    visit_done          BOOLEAN      NOT NULL DEFAULT FALSE,
    offer_expires_on    DATE,
    refusal_reason      VARCHAR(300),
    child_id            UUID REFERENCES child (id),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    CHECK ((birth_date IS NULL) <> (expected_birth_date IS NULL)),
    CHECK (leaves_at IS NULL OR arrives_at IS NULL OR leaves_at > arrives_at),
    CHECK (stage <> 'REFUSEE' OR refusal_reason IS NOT NULL)
);
CREATE INDEX enrollment_request_structure_idx ON enrollment_request (structure_id, stage);

-- Jours souhaités, ISO-8601 (1 = lundi)
CREATE TABLE enrollment_request_day (
    request_id  UUID    NOT NULL REFERENCES enrollment_request (id) ON DELETE CASCADE,
    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    PRIMARY KEY (request_id, day_of_week)
);

CREATE TABLE enrollment_request_criterion (
    request_id   UUID NOT NULL REFERENCES enrollment_request (id) ON DELETE CASCADE,
    criterion_id UUID NOT NULL REFERENCES enrollment_criterion (id) ON DELETE CASCADE,
    PRIMARY KEY (request_id, criterion_id)
);

CREATE TABLE enrollment_note (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID          NOT NULL REFERENCES structure (id),
    request_id   UUID          NOT NULL REFERENCES enrollment_request (id),
    body         VARCHAR(2000) NOT NULL,
    author       VARCHAR(170)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL
);
CREATE INDEX enrollment_note_request_idx ON enrollment_note (request_id);

-- Historique affiché sur la fiche demande (le journal d'audit reste la trace de référence).
CREATE TABLE enrollment_event (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    request_id   UUID         NOT NULL REFERENCES enrollment_request (id),
    label        VARCHAR(300) NOT NULL,
    author       VARCHAR(170) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX enrollment_event_request_idx ON enrollment_event (request_id);

-- ---------------------------------------------------------------------------
-- Contrats d'accueil et avenants
-- ---------------------------------------------------------------------------
CREATE TABLE contract (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id       UUID        NOT NULL REFERENCES structure (id),
    child_id           UUID        NOT NULL REFERENCES child (id),
    request_id         UUID REFERENCES enrollment_request (id),
    number             VARCHAR(20) NOT NULL,
    start_date         DATE        NOT NULL,
    end_date           DATE        NOT NULL,
    notice_months      INTEGER     NOT NULL CHECK (notice_months BETWEEN 0 AND 3),
    adaptation_billing VARCHAR(30) NOT NULL
        CHECK (adaptation_billing IN ('AU_TEMPS', 'PREMIERE_HEURE_OFFERTE', 'OFFERTE')),
    adaptation_rate    BIGINT      NOT NULL CHECK (adaptation_rate >= 0),
    adaptation_cost    BIGINT      NOT NULL CHECK (adaptation_cost >= 0),
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT contract_number_uk UNIQUE (structure_id, number),
    CHECK (end_date > start_date)
);
CREATE INDEX contract_child_idx ON contract (child_id);

-- Séances d'adaptation, avant le début du contrat
CREATE TABLE adaptation_session (
    contract_id  UUID        NOT NULL REFERENCES contract (id) ON DELETE CASCADE,
    session_date DATE        NOT NULL,
    starts_at    TIME        NOT NULL,
    ends_at      TIME        NOT NULL,
    note         VARCHAR(80),
    PRIMARY KEY (contract_id, session_date),
    CHECK (ends_at > starts_at)
);

-- Version 0 = contrat initial, puis un avenant par version. Le calcul du forfait est figé dans la version.
CREATE TABLE contract_version (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id             UUID         NOT NULL REFERENCES structure (id),
    contract_id              UUID         NOT NULL REFERENCES contract (id),
    number                   INTEGER      NOT NULL CHECK (number >= 0),
    effective_from           DATE         NOT NULL,
    reason                   VARCHAR(200),
    planning_mode            VARCHAR(10)  NOT NULL CHECK (planning_mode IN ('FIXE', 'VARIABLE')),
    monthly_reserved_minutes INTEGER CHECK (monthly_reserved_minutes > 0),
    planning_notice_days     INTEGER CHECK (planning_notice_days > 0),
    family_quotient          BIGINT CHECK (family_quotient >= 0),
    status                   VARCHAR(10)  NOT NULL CHECK (status IN ('BROUILLON', 'SIGNE')),
    signed_on                DATE,
    signed_document_id       UUID REFERENCES child_document (id),
    tariff_schedule_id       UUID         NOT NULL REFERENCES tariff_schedule (id),
    tariff_criterion         VARCHAR(20)  NOT NULL,
    band_lower_bound         BIGINT       NOT NULL,
    hourly_rate              BIGINT       NOT NULL CHECK (hourly_rate > 0),
    weeks_per_year           INTEGER      NOT NULL,
    weekly_minutes           INTEGER      NOT NULL CHECK (weekly_minutes >= 0),
    monthly_minutes          INTEGER      NOT NULL CHECK (monthly_minutes >= 0),
    monthly_fee              BIGINT       NOT NULL CHECK (monthly_fee >= 0),
    registration_fee         BIGINT       NOT NULL CHECK (registration_fee >= 0),
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT contract_version_number_uk UNIQUE (contract_id, number),
    CHECK (status <> 'SIGNE' OR signed_on IS NOT NULL),
    CHECK (planning_mode <> 'VARIABLE' OR monthly_reserved_minutes IS NOT NULL)
);
CREATE INDEX contract_version_contract_idx ON contract_version (contract_id);

-- Planning type d'une version, ISO-8601 (1 = lundi)
CREATE TABLE contract_schedule_day (
    version_id  UUID    NOT NULL REFERENCES contract_version (id) ON DELETE CASCADE,
    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    arrives_at  TIME    NOT NULL,
    leaves_at   TIME    NOT NULL,
    PRIMARY KEY (version_id, day_of_week),
    CHECK (leaves_at > arrives_at)
);

-- Une version signée ne se modifie plus : toute correction passe par un avenant.
-- (La suppression reste possible pour la purge des données d'une famille.)
CREATE FUNCTION forbid_signed_contract_update() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'La version % du contrat % est signée : elle ne peut plus être modifiée', OLD.number, OLD.contract_id;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER contract_version_signed_immutable
    BEFORE UPDATE ON contract_version
    FOR EACH ROW WHEN (OLD.status = 'SIGNE') EXECUTE FUNCTION forbid_signed_contract_update();

-- Critères de priorité proposés par défaut (maquette Fiche demande), modifiables dans Paramètres.
INSERT INTO enrollment_criterion (structure_id, label, points, valid_from, display_order, created_at, updated_at)
SELECT s.id, c.label, c.points, DATE '2020-01-01', c.display_order, now(), now()
FROM structure s
CROSS JOIN (VALUES ('Fratrie déjà accueillie', 3, 0),
                   ('Famille domiciliée dans la commune', 2, 1),
                   ('Parents en activité ou en formation', 2, 2),
                   ('Parent seul', 2, 3),
                   ('Situation signalée par un service social', 3, 4),
                   ('Besoin d’accueil particulier', 2, 5)) AS c(label, points, display_order);
