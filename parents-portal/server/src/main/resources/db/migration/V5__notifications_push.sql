-- Notifications sur le téléphone des parents (ADR 0018, Web Push).

-- Secrets du portail générés au premier démarrage (clés VAPID), quand l'hébergeur ne les fournit pas.
CREATE TABLE portal_secret (
    name       VARCHAR(40) PRIMARY KEY,
    value      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- Abonnement d'un appareil, rattaché à l'adresse e-mail de la session. categories : liste séparée par des virgules
-- (PHOTOS, MESSAGES, ACTUALITES, DOCUMENTS, FACTURES).
CREATE TABLE push_subscription (
    id              UUID PRIMARY KEY,
    email           VARCHAR(150)  NOT NULL,
    endpoint        VARCHAR(1000) NOT NULL UNIQUE,
    p256dh          VARCHAR(120)  NOT NULL,
    auth            VARCHAR(40)   NOT NULL,
    categories      VARCHAR(100)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    last_success_at TIMESTAMPTZ,
    failures        INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX push_subscription_email_idx ON push_subscription (lower(email));
