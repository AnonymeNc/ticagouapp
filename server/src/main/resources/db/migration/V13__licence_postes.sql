-- Licence (ADR 0011) : évaluation datée, lecture seule après la grâce, PC du bureau comptés.

-- Plus grande date déjà vue par le serveur : reculer l'horloge du PC ne prolonge ni l'évaluation ni la licence.
ALTER TABLE installation ADD COLUMN last_seen_on DATE;

-- PC du bureau qui se sont connectés (client bureau ou navigateur). L'identifiant est généré par le client et
-- seul son haché est gardé ; un PC retiré par la direction ne peut plus se connecter.
CREATE TABLE desktop_client (
    id             UUID PRIMARY KEY,
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    client_hash    VARCHAR(64)  NOT NULL,
    label          VARCHAR(120) NOT NULL,
    first_seen_at  TIMESTAMPTZ  NOT NULL,
    last_seen_at   TIMESTAMPTZ  NOT NULL,
    last_user_name VARCHAR(170),
    revoked_at     TIMESTAMPTZ,
    revoked_by     VARCHAR(170),
    UNIQUE (structure_id, client_hash)
);
