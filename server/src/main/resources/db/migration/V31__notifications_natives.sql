-- Notifications natives des applications (ADR 0026) : appareils inscrits par la direction (application Direction).
-- Un jeton par appareil et par application ; « poste_hash » relie l'appareil au poste (ADR 0028) : retirer le poste
-- efface ses notifications. Catégories choisies par la personne, séparées par des virgules.
CREATE TABLE notification_device (
    id            UUID PRIMARY KEY,
    structure_id  UUID         NOT NULL REFERENCES structure (id),
    user_id       UUID         NOT NULL REFERENCES app_user (id),
    poste_hash    VARCHAR(64),
    platform      VARCHAR(10)  NOT NULL CHECK (platform IN ('IOS', 'ANDROID')),
    app           VARCHAR(20)  NOT NULL,
    token         VARCHAR(400) NOT NULL UNIQUE,
    categories    VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    last_seen_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX notification_device_structure_idx ON notification_device (structure_id);

-- Une alerte par dépassement d'encadrement qui dure, pas une par minute.
ALTER TABLE ratio_breach ADD COLUMN notified_at TIMESTAMPTZ;

-- Tablette hors connexion pendant l'ouverture : une alerte par absence.
ALTER TABLE device ADD COLUMN offline_notified_at TIMESTAMPTZ;
