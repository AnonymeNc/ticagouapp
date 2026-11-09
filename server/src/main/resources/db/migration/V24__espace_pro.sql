-- Lot 4 : espace pro (demandes du personnel, cahier d'équipe, relevés HACCP, agenda partagé).

-- Demande d'un membre du personnel : congé ou récupération (jours), heures supplémentaires (minutes un jour donné).
CREATE TABLE staff_request (
    id           UUID PRIMARY KEY,
    structure_id UUID         NOT NULL REFERENCES structure (id),
    user_id      UUID         NOT NULL REFERENCES app_user (id),
    kind         VARCHAR(15)  NOT NULL CHECK (kind IN ('CONGES', 'RECUPERATION', 'HEURES_SUP')),
    from_day     DATE         NOT NULL,
    to_day       DATE         NOT NULL CHECK (to_day >= from_day),
    minutes      INTEGER CHECK (minutes IS NULL OR minutes BETWEEN 15 AND 720),
    note         VARCHAR(300),
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('DEMANDEE', 'ACCEPTEE', 'REFUSEE', 'ANNULEE')),
    answer       VARCHAR(300),
    absence_id   UUID REFERENCES staff_absence (id) ON DELETE SET NULL,
    requested_at TIMESTAMPTZ  NOT NULL,
    handled_by   VARCHAR(170),
    handled_at   TIMESTAMPTZ,
    CHECK (kind <> 'HEURES_SUP' OR (minutes IS NOT NULL AND from_day = to_day))
);
CREATE INDEX staff_request_user_idx ON staff_request (user_id, from_day);
CREATE INDEX staff_request_status_idx ON staff_request (structure_id, status);

-- Cahier d'équipe : notes entre pros, pour toute l'équipe ou une section ; lecture accusée.
CREATE TABLE team_note (
    id           UUID PRIMARY KEY,
    structure_id UUID          NOT NULL REFERENCES structure (id),
    section_id   UUID REFERENCES section (id),
    body         VARCHAR(1000) NOT NULL,
    author_id    UUID REFERENCES app_user (id),
    author_name  VARCHAR(170)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL
);
CREATE INDEX team_note_idx ON team_note (structure_id, created_at DESC);

CREATE TABLE team_note_read (
    note_id UUID        NOT NULL REFERENCES team_note (id) ON DELETE CASCADE,
    user_id UUID        NOT NULL REFERENCES app_user (id),
    read_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (note_id, user_id)
);

-- Relevés de températures (HACCP) : points de contrôle avec seuils, relevés en degrés au dixième.
CREATE TABLE haccp_point (
    id           UUID PRIMARY KEY,
    structure_id UUID          NOT NULL REFERENCES structure (id),
    label        VARCHAR(80)   NOT NULL,
    kind         VARCHAR(15)   NOT NULL CHECK (kind IN ('FRIGO', 'CONGELATEUR', 'PLAT_TEMOIN', 'LIVRAISON', 'AUTRE')),
    min_tenths   INTEGER       NOT NULL,
    max_tenths   INTEGER       NOT NULL CHECK (max_tenths >= min_tenths),
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL
);

CREATE TABLE haccp_reading (
    id           UUID PRIMARY KEY,
    structure_id UUID         NOT NULL REFERENCES structure (id),
    point_id     UUID         NOT NULL REFERENCES haccp_point (id),
    taken_at     TIMESTAMPTZ  NOT NULL,
    tenths       INTEGER      NOT NULL CHECK (tenths BETWEEN -500 AND 1500),
    out_of_range BOOLEAN      NOT NULL,
    action       VARCHAR(300),
    author_name  VARCHAR(170) NOT NULL,
    CHECK (NOT out_of_range OR action IS NOT NULL)
);
CREATE INDEX haccp_reading_idx ON haccp_reading (structure_id, taken_at);

-- Agenda partagé : lien privé par membre du personnel (empreinte seulement), révocable.
CREATE TABLE staff_calendar_token (
    user_id    UUID PRIMARY KEY REFERENCES app_user (id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);
