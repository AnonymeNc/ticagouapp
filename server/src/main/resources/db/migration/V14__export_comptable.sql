-- Export comptable (CSV paramétrable et FEC) : plan de comptes daté, réglages, comptes auxiliaires stables.
-- Les comptes par défaut sont semés au premier accès de chaque structure (AccountingSettingsService) : à faire
-- valider par l'expert-comptable de la crèche.

-- Compte général utilisé pour chaque rôle d'écriture, avec ses dates de validité (on n'écrase pas l'ancien compte).
CREATE TABLE accounting_account (
    id           UUID PRIMARY KEY,
    structure_id UUID         NOT NULL REFERENCES structure (id),
    role         VARCHAR(30)  NOT NULL CHECK (role IN ('PRODUITS_GARDE', 'PRODUITS_INSCRIPTION', 'PRODUITS_SUPPLEMENTS',
                                                       'TGC_COLLECTEE', 'CLIENTS_FAMILLES', 'CLIENTS_FINANCEURS',
                                                       'BANQUE', 'CAISSE', 'CHEQUES', 'CARTE', 'AVOIRS', 'ARRONDIS')),
    account      VARCHAR(20)  NOT NULL CHECK (account ~ '^[0-9A-Z]{3,20}$'),
    label        VARCHAR(100) NOT NULL,
    valid_from   DATE         NOT NULL,
    valid_to     DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX accounting_account_idx ON accounting_account (structure_id, role, valid_from);

-- Journaux, préfixes des comptes auxiliaires et format du CSV, une ligne par structure.
CREATE TABLE accounting_settings (
    structure_id   UUID PRIMARY KEY REFERENCES structure (id),
    sales_journal  VARCHAR(6)   NOT NULL,
    bank_journal   VARCHAR(6)   NOT NULL,
    cash_journal   VARCHAR(6)   NOT NULL,
    family_prefix  VARCHAR(6)   NOT NULL,
    funder_prefix  VARCHAR(6)   NOT NULL,
    csv_separator  VARCHAR(3)   NOT NULL CHECK (csv_separator IN (';', ',', 'TAB')),
    csv_date       VARCHAR(10)  NOT NULL CHECK (csv_date IN ('JJ/MM/AAAA', 'AAAA-MM-JJ', 'AAAAMMJJ')),
    csv_columns    VARCHAR(200) NOT NULL,
    csv_header     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0
);

-- Numéro de compte auxiliaire attribué une fois pour toutes à une famille ou à un financeur (F00001, O00001…).
CREATE TABLE accounting_party (
    structure_id UUID        NOT NULL REFERENCES structure (id),
    party_type   VARCHAR(10) NOT NULL CHECK (party_type IN ('FAMILLE', 'FINANCEUR')),
    party_id     UUID        NOT NULL,
    code         VARCHAR(20) NOT NULL,
    PRIMARY KEY (structure_id, party_type, party_id),
    UNIQUE (structure_id, code)
);
