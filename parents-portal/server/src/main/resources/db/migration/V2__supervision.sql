-- Supervision des serveurs de crèche (ADR 0012) : indépendante du portail parents. Chaque serveur activé par la
-- direction envoie toutes les 15 minutes un état technique signé, sans aucune donnée personnelle ; le prestataire
-- reçoit les alertes par e-mail.

CREATE TABLE supervision_code (
    code_hash  VARCHAR(64) PRIMARY KEY,
    label      VARCHAR(150),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    site_id    UUID
);

CREATE TABLE supervision_site (
    id           UUID PRIMARY KEY,
    name         VARCHAR(150) NOT NULL,
    public_key   BYTEA        NOT NULL,
    activated_at TIMESTAMPTZ  NOT NULL,
    last_seen_at TIMESTAMPTZ,
    last_status  JSONB,
    suspended_at TIMESTAMPTZ
);

-- Alerte ouverte (closed_at nul) ou résolue ; une seule alerte ouverte par site et par type.
CREATE TABLE supervision_alert (
    id        BIGSERIAL PRIMARY KEY,
    site_id   UUID         NOT NULL REFERENCES supervision_site (id) ON DELETE CASCADE,
    kind      VARCHAR(20)  NOT NULL CHECK (kind IN ('SILENCE', 'SAUVEGARDE', 'DISQUE', 'LICENCE', 'TABLETTES', 'PORTAIL')),
    detail    VARCHAR(500) NOT NULL,
    opened_at TIMESTAMPTZ  NOT NULL,
    closed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX supervision_alert_open_idx ON supervision_alert (site_id, kind) WHERE closed_at IS NULL;
