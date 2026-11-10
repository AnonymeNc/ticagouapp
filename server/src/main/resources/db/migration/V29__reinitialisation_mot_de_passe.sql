-- Lien de réinitialisation du mot de passe, créé par l'éditeur (console, ordre MOT_DE_PASSE ; creche.sh mot-de-passe)
-- quand la direction l'a oublié. Seule l'empreinte SHA-256 du jeton est gardée ; usage unique, 24 heures.
CREATE TABLE password_reset (
    token_hash   CHAR(64)    PRIMARY KEY,
    structure_id UUID        NOT NULL REFERENCES structure (id),
    user_id      UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ
);

CREATE INDEX password_reset_user ON password_reset (user_id);
