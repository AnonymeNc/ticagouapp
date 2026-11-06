-- Option B (ADR 0013) : une instance hébergée par crèche.

-- L'abonnement remplace la clé de licence : une ligne par instance, écrite par l'éditeur (deploy/creche.sh).
-- Pas de ligne : évaluation. modules NULL : tous les modules.
DROP TABLE license;
CREATE TABLE subscription (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    number      VARCHAR(40)  NOT NULL,
    holder      VARCHAR(150) NOT NULL,
    plan        VARCHAR(100) NOT NULL,
    places      INTEGER      NOT NULL CHECK (places > 0),
    desktops    INTEGER      NOT NULL CHECK (desktops > 0),
    tablets     INTEGER      NOT NULL CHECK (tablets >= 0),
    valid_until DATE         NOT NULL,
    modules     VARCHAR(200),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Sauvegardes : dossier, heure et clé viennent de la configuration de l'instance, plus de la direction.
ALTER TABLE backup_settings
    DROP COLUMN daily_time,
    DROP COLUMN destination,
    DROP COLUMN offsite,
    DROP COLUMN key_handed_on,
    DROP COLUMN key_handed_by;

-- Double authentification (TOTP). totp_last_step : dernier pas de 30 s accepté, un code ne sert qu'une fois.
ALTER TABLE app_user
    ADD COLUMN totp_secret     VARCHAR(64),
    ADD COLUMN totp_enabled_at TIMESTAMPTZ,
    ADD COLUMN totp_last_step  BIGINT;
