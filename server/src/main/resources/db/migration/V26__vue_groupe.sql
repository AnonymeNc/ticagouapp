-- Lot 7 : vue groupe. Une crèche qui rejoint un groupe (code commun, consentement de la direction) joint quelques
-- indicateurs agrégés à son état de supervision ; les directions du même groupe les lisent. Rien n'est partagé sans
-- ce consentement, retirable à tout moment.
ALTER TABLE monitoring_settings
    ADD COLUMN group_code_hash VARCHAR(64),
    ADD COLUMN group_url       VARCHAR(255),
    ADD COLUMN group_joined_at TIMESTAMPTZ,
    ADD COLUMN group_joined_by VARCHAR(170);
