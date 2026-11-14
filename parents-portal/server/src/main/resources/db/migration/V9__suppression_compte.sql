-- Suppression du compte depuis l'application Ti'Cagou Parents (ADR 0024, exigence d'Apple) : l'adresse ne peut plus
-- se connecter tant que la crèche ne l'a pas renvoyée (réinvitation ou fiche modifiée). Les données de la famille
-- restent à la crèche, prévenue par un message : elle les purge selon ses obligations (PURGE_FAMILY).
CREATE TABLE account_deletion (
    email       VARCHAR(150) PRIMARY KEY,
    deleted_at  TIMESTAMPTZ  NOT NULL
);
