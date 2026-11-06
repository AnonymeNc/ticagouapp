-- Phase 9 : rotation 7 jours / 4 semaines / 12 mois, sauvegarde automatique avant chaque migration.

ALTER TABLE backup_settings
    ADD COLUMN keep_weeks INTEGER NOT NULL DEFAULT 4 CHECK (keep_weeks BETWEEN 0 AND 52),
    ALTER COLUMN keep_days SET DEFAULT 7;

-- Les réglages encore à leur valeur d'origine (30 jours) passent à la nouvelle rotation.
UPDATE backup_settings SET keep_days = 7 WHERE keep_days = 30;

ALTER TABLE backup_run DROP CONSTRAINT backup_run_kind_check;
ALTER TABLE backup_run ADD CONSTRAINT backup_run_kind_check
    CHECK (kind IN ('AUTOMATIQUE', 'MANUELLE', 'TEST', 'AVANT_MIGRATION'));
