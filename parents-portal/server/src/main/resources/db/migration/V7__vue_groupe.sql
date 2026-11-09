-- Lot 7 : vue groupe. Dernier bloc « groupe » reçu d'un site (indicateurs agrégés, seulement si la crèche a rejoint un
-- groupe) ; les sites d'un même groupe se reconnaissent par l'empreinte du code commun.
ALTER TABLE supervision_site
    ADD COLUMN group_hash     VARCHAR(64),
    ADD COLUMN group_snapshot JSONB;
CREATE INDEX supervision_site_group_idx ON supervision_site (group_hash) WHERE group_hash IS NOT NULL;
