-- Parc des crèches (plan console, phase 2) : clients, crèches, états reçus de la supervision et de l'hôte,
-- historique des alertes. Un seul locataire (l'éditeur) : pas de structure_id (ADR 0017, D7).

CREATE TABLE client (
    id             UUID PRIMARY KEY,
    name           VARCHAR(150) NOT NULL,
    kind           VARCHAR(15)  NOT NULL DEFAULT 'PRIVE'
        CHECK (kind IN ('PRIVE', 'ASSOCIATION', 'COLLECTIVITE', 'ENTREPRISE')),
    ridet          VARCHAR(20),
    address        VARCHAR(400),
    director_name  VARCHAR(150),
    director_email VARCHAR(200),
    director_phone VARCHAR(40),
    billing_email  VARCHAR(200),
    reminder_email VARCHAR(200),
    -- Délai de paiement : 30 jours ; collectivité, 45 jours (mandat administratif).
    payment_days   INTEGER      NOT NULL DEFAULT 30 CHECK (payment_days BETWEEN 0 AND 120),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);

-- Une crèche hébergée : son slug est la clé commune avec deploy/creche.sh et la supervision.
CREATE TABLE creche (
    id                    UUID PRIMARY KEY,
    slug                  VARCHAR(60)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,40}$'),
    name                  VARCHAR(150) NOT NULL,
    city                  VARCHAR(80),
    client_id             UUID         NOT NULL REFERENCES client (id),
    -- PROSPECT : pas encore d'instance ; CLIENT : instance ouverte ; RESILIE : contrat terminé.
    -- Évaluation, grâce, lecture seule et suspension se lisent dans l'état reçu (licence, conteneur).
    lifecycle             VARCHAR(10)  NOT NULL DEFAULT 'CLIENT' CHECK (lifecycle IN ('PROSPECT', 'CLIENT', 'RESILIE')),
    client_since          DATE,
    supervision_site_id   UUID,
    supervision_name      VARCHAR(150),
    supervision_seen_at   TIMESTAMPTZ,
    supervision_status    JSONB,
    supervision_suspended BOOLEAN      NOT NULL DEFAULT FALSE,
    host_seen_at          TIMESTAMPTZ,
    host                  JSONB,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);
CREATE INDEX creche_client_idx ON creche (client_id);

-- Notes et appels sur une crèche (« Noter un appel »).
CREATE TABLE creche_note (
    id             BIGSERIAL PRIMARY KEY,
    creche_id      UUID         NOT NULL REFERENCES creche (id) ON DELETE CASCADE,
    at             TIMESTAMPTZ  NOT NULL,
    operator_login VARCHAR(60),
    kind           VARCHAR(10)  NOT NULL CHECK (kind IN ('NOTE', 'APPEL')),
    text           VARCHAR(2000) NOT NULL
);
CREATE INDEX creche_note_creche_idx ON creche_note (creche_id, at DESC);

-- Instantanés : un par état de supervision reçu ; au-delà de 30 jours, un seul par jour (le dernier).
CREATE TABLE creche_snapshot (
    id        BIGSERIAL PRIMARY KEY,
    creche_id UUID        NOT NULL REFERENCES creche (id) ON DELETE CASCADE,
    at        TIMESTAMPTZ NOT NULL,
    data      JSONB       NOT NULL
);
CREATE INDEX creche_snapshot_creche_idx ON creche_snapshot (creche_id, at);

-- Copie des alertes de la supervision (le portail ne garde que l'état courant de chaque site) ; id = celui du portail.
CREATE TABLE alert_history (
    id        BIGINT PRIMARY KEY,
    creche_id UUID         NOT NULL REFERENCES creche (id) ON DELETE CASCADE,
    kind      VARCHAR(20)  NOT NULL,
    detail    VARCHAR(500) NOT NULL,
    opened_at TIMESTAMPTZ  NOT NULL,
    closed_at TIMESTAMPTZ
);
CREATE INDEX alert_history_creche_idx ON alert_history (creche_id, opened_at DESC);

-- Dernière lecture de chaque source : SUPERVISION (portail), ALERTES (portail), HOTE (creche.sh rapport-console).
CREATE TABLE collector (
    source          VARCHAR(12) PRIMARY KEY CHECK (source IN ('SUPERVISION', 'ALERTES', 'HOTE')),
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error      VARCHAR(500),
    cursor_at       TIMESTAMPTZ,
    data            JSONB
);
INSERT INTO collector (source) VALUES ('SUPERVISION'), ('ALERTES'), ('HOTE');
