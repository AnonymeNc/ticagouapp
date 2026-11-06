-- Mode de facturation des contrats à planning fixe : mensualisé (même montant chaque mois) ou au réel (jours du
-- planning du mois). Le défaut est réglé dans les règles de facturation datées ; chaque contrat peut en changer.

ALTER TABLE billing_rule
    ADD COLUMN default_billing_mode VARCHAR(12) NOT NULL DEFAULT 'MENSUALISE'
        CHECK (default_billing_mode IN ('MENSUALISE', 'REEL'));

ALTER TABLE contract_version
    ADD COLUMN billing_mode VARCHAR(12) NOT NULL DEFAULT 'MENSUALISE'
        CHECK (billing_mode IN ('MENSUALISE', 'REEL'));
