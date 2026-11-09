-- Reporting (plan console, phase 6) : temps de support saisi sur les notes et les appels, réglages de la rentabilité.

-- Durée d'un appel ou d'une intervention, en minutes : le barème suppose 0,75 h par crèche et par mois.
ALTER TABLE creche_note ADD COLUMN minutes INTEGER CHECK (minutes IS NULL OR minutes BETWEEN 1 AND 1440);

-- Rentabilité par crèche : coût d'une heure de support et hébergement réparti, en francs CFP par mois.
CREATE TABLE reporting_settings (
    id                  INTEGER PRIMARY KEY CHECK (id = 1),
    support_hourly_rate BIGINT      NOT NULL CHECK (support_hourly_rate >= 0),
    hosting_per_creche  BIGINT      NOT NULL CHECK (hosting_per_creche >= 0),
    updated_at          TIMESTAMPTZ NOT NULL
);
INSERT INTO reporting_settings (id, support_hourly_rate, hosting_per_creche, updated_at) VALUES (1, 9000, 1200, now());
