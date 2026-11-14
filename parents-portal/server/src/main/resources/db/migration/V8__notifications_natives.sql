-- Application Ti'Cagou Parents (ADR 0026) : jetons APNs (iPhone) et FCM (Android), à côté des abonnements Web Push.
-- Rattachés à l'adresse de la session, avec les catégories choisies par le parent pour cet appareil.
CREATE TABLE native_device (
    id           UUID PRIMARY KEY,
    email        VARCHAR(150) NOT NULL,
    platform     VARCHAR(10)  NOT NULL CHECK (platform IN ('IOS', 'ANDROID')),
    token        VARCHAR(400) NOT NULL UNIQUE,
    categories   VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX native_device_email_idx ON native_device (lower(email));
