-- Lot 2 : demandes d'accueil occasionnel et retards annoncés par les parents depuis le portail.
-- id = référence de l'élément du portail : la réponse de la crèche y remplace la demande du parent.
CREATE TABLE booking_request (
    id             UUID PRIMARY KEY,
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    family_id      UUID         NOT NULL REFERENCES family (id),
    child_id       UUID         NOT NULL REFERENCES child (id),
    kind           VARCHAR(12)  NOT NULL CHECK (kind IN ('RESERVATION', 'RETARD')),
    day            DATE         NOT NULL,
    arrives_at     TIME,
    leaves_at      TIME         NOT NULL,
    note           VARCHAR(300),
    guardian_name  VARCHAR(120),
    requested_at   TIMESTAMPTZ  NOT NULL,
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('DEMANDEE', 'ACCEPTEE', 'REFUSEE', 'ANNULEE')),
    answer         VARCHAR(300),
    over_capacity  BOOLEAN      NOT NULL DEFAULT FALSE,
    handled_by     VARCHAR(120),
    handled_at     TIMESTAMPTZ,
    CHECK (kind = 'RETARD' OR (arrives_at IS NOT NULL AND arrives_at < leaves_at))
);
CREATE INDEX booking_request_day_idx ON booking_request (structure_id, day);
CREATE INDEX booking_request_status_idx ON booking_request (structure_id, status);

-- Règles des demandes, datées : délai minimum avant l'accueil, horizon de réservation.
CREATE TABLE booking_rule (
    id               UUID PRIMARY KEY,
    structure_id     UUID    NOT NULL REFERENCES structure (id),
    valid_from       DATE    NOT NULL,
    valid_to         DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    min_notice_hours INTEGER NOT NULL CHECK (min_notice_hours BETWEEN 0 AND 336),
    max_days_ahead   INTEGER NOT NULL CHECK (max_days_ahead BETWEEN 1 AND 365),
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX booking_rule_idx ON booking_rule (structure_id, valid_from);
