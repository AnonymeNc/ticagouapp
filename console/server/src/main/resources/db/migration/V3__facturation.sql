-- Facturation de l'éditeur aux crèches (plan console, phase 3). Montants en francs CFP entiers (BIGINT), jamais de
-- décimale ; pourcentages en dixièmes (110 = 11,0 %). Barème et TGC : tables à dates de validité, rien en dur.

-- --- Barème ----------------------------------------------------------------------------------------------------

-- Une version du barème : ses prix s'appliquent aux contrats signés pendant sa validité (tarif figé à la signature).
CREATE TABLE price_list (
    id                       UUID PRIMARY KEY,
    label                    VARCHAR(80) NOT NULL,
    valid_from               DATE        NOT NULL,
    valid_to                 DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Tranches de places : Micro jusqu'à micro_max, Structure jusqu'à structure_max, Grande jusqu'à grande_max ;
    -- au-delà, sur devis (remise libre).
    micro_max                INTEGER     NOT NULL CHECK (micro_max > 0),
    structure_max            INTEGER     NOT NULL,
    grande_max               INTEGER     NOT NULL,
    included_desktops        INTEGER     NOT NULL CHECK (included_desktops >= 0),
    included_tablets         INTEGER     NOT NULL CHECK (included_tablets >= 0),
    engagement3_discount     INTEGER     NOT NULL CHECK (engagement3_discount BETWEEN 0 AND 1000),
    extra_structure_discount INTEGER     NOT NULL CHECK (extra_structure_discount BETWEEN 0 AND 1000),
    created_at               TIMESTAMPTZ NOT NULL,
    CHECK (micro_max < structure_max AND structure_max < grande_max)
);

-- Prix mensuel (abonnement : formule, module, équipement, support) ou unitaire (prestation ponctuelle).
CREATE TABLE price_item (
    id            UUID PRIMARY KEY,
    price_list_id UUID         NOT NULL REFERENCES price_list (id) ON DELETE CASCADE,
    kind          VARCHAR(10)  NOT NULL CHECK (kind IN ('FORMULE', 'MODULE', 'EQUIPEMENT', 'SUPPORT', 'PRESTATION')),
    code          VARCHAR(30)  NOT NULL,
    tier          VARCHAR(10) CHECK (tier IN ('MICRO', 'STRUCTURE', 'GRANDE')),
    label         VARCHAR(120) NOT NULL,
    price         BIGINT       NOT NULL CHECK (price >= 0),
    unit          VARCHAR(20),
    -- Formule : modules compris (« INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS »).
    includes      VARCHAR(200),
    tgc_nature    VARCHAR(10)  NOT NULL CHECK (tgc_nature IN ('ABONNEMENT', 'PRESTATION', 'MATERIEL')),
    position      INTEGER      NOT NULL
);
CREATE UNIQUE INDEX price_item_key_uk ON price_item (price_list_id, kind, code, coalesce(tier, ''));

-- Taux de TGC par nature de prestation, avec dates de validité (à confirmer par le comptable).
CREATE TABLE tgc_rate (
    id         UUID PRIMARY KEY,
    nature     VARCHAR(10)  NOT NULL CHECK (nature IN ('ABONNEMENT', 'PRESTATION', 'MATERIEL')),
    rate       INTEGER      NOT NULL CHECK (rate BETWEEN 0 AND 1000),
    valid_from DATE         NOT NULL,
    valid_to   DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    note       VARCHAR(200)
);

-- Mentions de l'éditeur, recopiées dans chaque facture à sa validation (une facture validée ne change plus).
CREATE TABLE issuer (
    id             INTEGER PRIMARY KEY CHECK (id = 1),
    name           VARCHAR(150),
    legal_form     VARCHAR(80),
    ridet          VARCHAR(20),
    tgc_number     VARCHAR(40),
    address        VARCHAR(400),
    email          VARCHAR(200),
    phone          VARCHAR(40),
    bank           VARCHAR(120),
    iban           VARCHAR(60),
    payment_terms  VARCHAR(500),
    late_penalties VARCHAR(500),
    footer         VARCHAR(500),
    -- Copie des e-mails de la boîte d'envoi (facturation@…) ; vide : pas de copie.
    copy_email     VARCHAR(200),
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Numérotation continue par série (F, AV) et par exercice ; l'incrément est atomique.
CREATE TABLE counter (
    series VARCHAR(4) NOT NULL,
    year   INTEGER    NOT NULL,
    value  INTEGER    NOT NULL,
    PRIMARY KEY (series, year)
);

-- --- Contrats ---------------------------------------------------------------------------------------------------

-- Contrat d'abonnement d'une crèche. Un avenant ferme la version en cours (ends_on) et en ouvre une autre.
CREATE TABLE subscription_contract (
    id               UUID PRIMARY KEY,
    creche_id        UUID         NOT NULL REFERENCES creche (id),
    client_id        UUID         NOT NULL REFERENCES client (id),
    price_list_id    UUID         NOT NULL REFERENCES price_list (id),
    plan             VARCHAR(10)  NOT NULL CHECK (plan IN ('ESSENTIEL', 'GESTION', 'COMPLET')),
    places           INTEGER      NOT NULL CHECK (places > 0),
    -- Modules ajoutés à la formule (« REPAS,PERSONNEL »).
    modules          VARCHAR(200) NOT NULL DEFAULT '',
    extra_desktops   INTEGER      NOT NULL DEFAULT 0 CHECK (extra_desktops >= 0),
    extra_tablets    INTEGER      NOT NULL DEFAULT 0 CHECK (extra_tablets >= 0),
    premium_support  BOOLEAN      NOT NULL DEFAULT FALSE,
    engagement_years INTEGER      NOT NULL CHECK (engagement_years IN (1, 3)),
    rhythm           VARCHAR(8)   NOT NULL CHECK (rhythm IN ('ANNUEL', 'MENSUEL')),
    extra_structure  BOOLEAN      NOT NULL DEFAULT FALSE,
    free_discount    INTEGER      NOT NULL DEFAULT 0 CHECK (free_discount BETWEEN 0 AND 1000),
    free_discount_reason VARCHAR(200),
    starts_on        DATE         NOT NULL,
    ends_on          DATE CHECK (ends_on IS NULL OR ends_on >= starts_on - 1),
    signed_on        DATE         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CHECK (free_discount = 0 OR free_discount_reason IS NOT NULL)
);
CREATE INDEX subscription_contract_creche_idx ON subscription_contract (creche_id, starts_on);
-- Une seule version sans fin par crèche.
CREATE UNIQUE INDEX subscription_contract_open_uk ON subscription_contract (creche_id) WHERE ends_on IS NULL;

-- --- Factures et avoirs -----------------------------------------------------------------------------------------

CREATE TABLE invoice (
    id               UUID PRIMARY KEY,
    type             VARCHAR(7)   NOT NULL CHECK (type IN ('FACTURE', 'AVOIR')),
    number           VARCHAR(20) UNIQUE,
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('BROUILLON', 'VALIDEE')),
    kind             VARCHAR(12)  NOT NULL CHECK (kind IN ('ABONNEMENT', 'COMPLEMENT', 'PRESTATION', 'AVOIR')),
    client_id        UUID         NOT NULL REFERENCES client (id),
    creche_id        UUID REFERENCES creche (id),
    contract_id      UUID REFERENCES subscription_contract (id),
    -- Avoir : facture qu'il corrige.
    corrects_id      UUID REFERENCES invoice (id),
    subject          VARCHAR(200) NOT NULL,
    period_from      DATE,
    period_to        DATE,
    issued_on        DATE,
    due_on           DATE,
    total_ht         BIGINT       NOT NULL DEFAULT 0,
    total_tgc        BIGINT       NOT NULL DEFAULT 0,
    total_ttc        BIGINT       NOT NULL DEFAULT 0,
    tgc_detail       JSONB,
    client_snapshot  JSONB,
    issuer_snapshot  JSONB,
    note             VARCHAR(1000),
    pdf              BYTEA,
    pdf_sha256       VARCHAR(64),
    sent_at          TIMESTAMPTZ,
    reminders_paused BOOLEAN      NOT NULL DEFAULT FALSE,
    validated_at     TIMESTAMPTZ,
    validated_by     VARCHAR(60),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CHECK (status = 'BROUILLON' OR (number IS NOT NULL AND issued_on IS NOT NULL AND pdf IS NOT NULL)),
    CHECK ((type = 'AVOIR') = (corrects_id IS NOT NULL)),
    CHECK ((type = 'AVOIR') = (kind = 'AVOIR')),
    CHECK (period_to IS NULL OR period_from IS NULL OR period_to >= period_from)
);
CREATE INDEX invoice_client_idx ON invoice (client_id, issued_on);
CREATE INDEX invoice_contract_idx ON invoice (contract_id, period_to);
CREATE INDEX invoice_corrects_idx ON invoice (corrects_id);

-- Lignes : montant HT ; un avoir porte des montants positifs (ce qui est rendu).
CREATE TABLE invoice_line (
    id          UUID PRIMARY KEY,
    invoice_id  UUID         NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    position    INTEGER      NOT NULL,
    label       VARCHAR(200) NOT NULL,
    detail      VARCHAR(300),
    quantity    INTEGER      NOT NULL CHECK (quantity > 0),
    unit_price  BIGINT       NOT NULL,
    amount      BIGINT       NOT NULL,
    tgc_nature  VARCHAR(10)  NOT NULL CHECK (tgc_nature IN ('ABONNEMENT', 'PRESTATION', 'MATERIEL')),
    tgc_rate    INTEGER      NOT NULL CHECK (tgc_rate BETWEEN 0 AND 1000),
    -- Avoir : ligne de la facture corrigée.
    credits_line_id UUID REFERENCES invoice_line (id)
);
CREATE INDEX invoice_line_invoice_idx ON invoice_line (invoice_id, position);

-- Une facture validée est immuable : seuls l'envoi et la suspension des relances changent encore.
CREATE FUNCTION invoice_frozen() RETURNS trigger AS $$
DECLARE
    probe invoice%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'BROUILLON' THEN
            RAISE EXCEPTION 'Facture % validée : elle ne se supprime pas, on la corrige par un avoir.', OLD.number;
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status <> 'BROUILLON' THEN
        probe := NEW;
        probe.sent_at := OLD.sent_at;
        probe.reminders_paused := OLD.reminders_paused;
        probe.updated_at := OLD.updated_at;
        IF probe IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Facture % validée : elle ne se modifie plus, on la corrige par un avoir.', OLD.number;
        END IF;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER invoice_frozen BEFORE UPDATE OR DELETE ON invoice FOR EACH ROW EXECUTE FUNCTION invoice_frozen();

CREATE FUNCTION invoice_line_frozen() RETURNS trigger AS $$
DECLARE
    state VARCHAR(10);
BEGIN
    SELECT status INTO state FROM invoice WHERE id = coalesce(NEW.invoice_id, OLD.invoice_id);
    IF state IS NOT NULL AND state <> 'BROUILLON' THEN
        RAISE EXCEPTION 'Facture validée : ses lignes ne changent plus.';
    END IF;
    RETURN coalesce(NEW, OLD);
END $$ LANGUAGE plpgsql;
CREATE TRIGGER invoice_line_frozen BEFORE INSERT OR UPDATE OR DELETE ON invoice_line
    FOR EACH ROW EXECUTE FUNCTION invoice_line_frozen();

-- --- Paiements --------------------------------------------------------------------------------------------------

CREATE TABLE payment (
    id             UUID PRIMARY KEY,
    client_id      UUID         NOT NULL REFERENCES client (id),
    received_on    DATE         NOT NULL,
    amount         BIGINT       NOT NULL CHECK (amount > 0),
    method         VARCHAR(10)  NOT NULL CHECK (method IN ('VIREMENT', 'CHEQUE', 'ESPECES', 'AUTRE')),
    reference      VARCHAR(100),
    note           VARCHAR(300),
    operator_login VARCHAR(60),
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX payment_client_idx ON payment (client_id, received_on);

-- Affectation d'un paiement à une facture ; le reste non affecté est un trop-perçu du client.
CREATE TABLE payment_allocation (
    id         UUID PRIMARY KEY,
    payment_id UUID   NOT NULL REFERENCES payment (id) ON DELETE CASCADE,
    invoice_id UUID   NOT NULL REFERENCES invoice (id),
    amount     BIGINT NOT NULL CHECK (amount > 0)
);
CREATE INDEX payment_allocation_invoice_idx ON payment_allocation (invoice_id);

-- --- Boîte d'envoi ----------------------------------------------------------------------------------------------

-- Message préparé : rien ne part sans un clic (plan console § 0 bis).
CREATE TABLE outbox_message (
    id              UUID PRIMARY KEY,
    kind            VARCHAR(8)    NOT NULL CHECK (kind IN ('FACTURE', 'AVOIR', 'RELANCE')),
    invoice_id      UUID REFERENCES invoice (id),
    client_id       UUID          NOT NULL REFERENCES client (id),
    creche_id       UUID REFERENCES creche (id),
    to_address      VARCHAR(400),
    cc_address      VARCHAR(400),
    subject         VARCHAR(200)  NOT NULL,
    body            VARCHAR(8000) NOT NULL,
    status          VARCHAR(8)    NOT NULL CHECK (status IN ('PREPARE', 'ENVOYE', 'ECARTE')),
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    sent_at         TIMESTAMPTZ,
    sent_by         VARCHAR(60),
    last_error      VARCHAR(500),
    discarded_at    TIMESTAMPTZ,
    discarded_by    VARCHAR(60),
    discard_reason  VARCHAR(300)
);
CREATE INDEX outbox_message_status_idx ON outbox_message (status, created_at DESC);

-- --- Valeurs de départ (docs/commercial/bareme-tarifaire.md), modifiables dans Paramètres ------------------------

INSERT INTO issuer (id, name, updated_at) VALUES (1, NULL, now());

INSERT INTO tgc_rate (id, nature, rate, valid_from, note) VALUES
    (gen_random_uuid(), 'ABONNEMENT', 110, DATE '2026-01-01', 'Taux de 11 % à confirmer par le comptable'),
    (gen_random_uuid(), 'PRESTATION', 110, DATE '2026-01-01', 'Taux de 11 % à confirmer par le comptable'),
    (gen_random_uuid(), 'MATERIEL', 110, DATE '2026-01-01', 'Taux de 11 % à confirmer par le comptable');

INSERT INTO price_list (id, label, valid_from, micro_max, structure_max, grande_max, included_desktops, included_tablets,
                        engagement3_discount, extra_structure_discount, created_at)
VALUES ('00000000-0000-0000-0000-000000002026', 'Barème 2026', DATE '2026-01-01', 15, 40, 80, 1, 2, 100, 150, now());

INSERT INTO price_item (id, price_list_id, kind, code, tier, label, price, unit, includes, tgc_nature, position)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000002026', kind, code, tier, label, price, unit, includes, nature, position
FROM (VALUES
    ('FORMULE', 'ESSENTIEL', 'MICRO', 'Essentiel Micro', 6500, NULL, '', 'ABONNEMENT', 10),
    ('FORMULE', 'ESSENTIEL', 'STRUCTURE', 'Essentiel Structure', 9500, NULL, '', 'ABONNEMENT', 11),
    ('FORMULE', 'ESSENTIEL', 'GRANDE', 'Essentiel Grande', 14000, NULL, '', 'ABONNEMENT', 12),
    ('FORMULE', 'GESTION', 'MICRO', 'Gestion Micro', 11500, NULL, 'INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS', 'ABONNEMENT', 20),
    ('FORMULE', 'GESTION', 'STRUCTURE', 'Gestion Structure', 16500, NULL, 'INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS', 'ABONNEMENT', 21),
    ('FORMULE', 'GESTION', 'GRANDE', 'Gestion Grande', 23500, NULL, 'INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS', 'ABONNEMENT', 22),
    ('FORMULE', 'COMPLET', 'MICRO', 'Complet Micro', 18500, NULL, 'INSCRIPTIONS,FACTURATION,COMPTABILITE,PERSONNEL,REPAS,PORTAIL_PARENTS', 'ABONNEMENT', 30),
    ('FORMULE', 'COMPLET', 'STRUCTURE', 'Complet Structure', 26000, NULL, 'INSCRIPTIONS,FACTURATION,COMPTABILITE,PERSONNEL,REPAS,PORTAIL_PARENTS', 'ABONNEMENT', 31),
    ('FORMULE', 'COMPLET', 'GRANDE', 'Complet Grande', 36500, NULL, 'INSCRIPTIONS,FACTURATION,COMPTABILITE,PERSONNEL,REPAS,PORTAIL_PARENTS', 'ABONNEMENT', 32),
    ('MODULE', 'INSCRIPTIONS', 'MICRO', 'Inscriptions et contrats', 2000, NULL, NULL, 'ABONNEMENT', 40),
    ('MODULE', 'INSCRIPTIONS', 'STRUCTURE', 'Inscriptions et contrats', 3000, NULL, NULL, 'ABONNEMENT', 41),
    ('MODULE', 'INSCRIPTIONS', 'GRANDE', 'Inscriptions et contrats', 4000, NULL, NULL, 'ABONNEMENT', 42),
    ('MODULE', 'FACTURATION', 'MICRO', 'Facturation et statistiques', 4000, NULL, NULL, 'ABONNEMENT', 43),
    ('MODULE', 'FACTURATION', 'STRUCTURE', 'Facturation et statistiques', 5500, NULL, NULL, 'ABONNEMENT', 44),
    ('MODULE', 'FACTURATION', 'GRANDE', 'Facturation et statistiques', 7500, NULL, NULL, 'ABONNEMENT', 45),
    ('MODULE', 'COMPTABILITE', 'MICRO', 'Comptabilité', 1000, NULL, NULL, 'ABONNEMENT', 46),
    ('MODULE', 'COMPTABILITE', 'STRUCTURE', 'Comptabilité', 1500, NULL, NULL, 'ABONNEMENT', 47),
    ('MODULE', 'COMPTABILITE', 'GRANDE', 'Comptabilité', 2000, NULL, NULL, 'ABONNEMENT', 48),
    ('MODULE', 'PERSONNEL', 'MICRO', 'Personnel', 2500, NULL, NULL, 'ABONNEMENT', 49),
    ('MODULE', 'PERSONNEL', 'STRUCTURE', 'Personnel', 3500, NULL, NULL, 'ABONNEMENT', 50),
    ('MODULE', 'PERSONNEL', 'GRANDE', 'Personnel', 5000, NULL, NULL, 'ABONNEMENT', 51),
    ('MODULE', 'REPAS', 'MICRO', 'Repas', 2000, NULL, NULL, 'ABONNEMENT', 52),
    ('MODULE', 'REPAS', 'STRUCTURE', 'Repas', 2500, NULL, NULL, 'ABONNEMENT', 53),
    ('MODULE', 'REPAS', 'GRANDE', 'Repas', 3500, NULL, NULL, 'ABONNEMENT', 54),
    ('MODULE', 'PORTAIL_PARENTS', 'MICRO', 'Portail parents', 3500, NULL, NULL, 'ABONNEMENT', 55),
    ('MODULE', 'PORTAIL_PARENTS', 'STRUCTURE', 'Portail parents', 5000, NULL, NULL, 'ABONNEMENT', 56),
    ('MODULE', 'PORTAIL_PARENTS', 'GRANDE', 'Portail parents', 7000, NULL, NULL, 'ABONNEMENT', 57),
    ('EQUIPEMENT', 'PC', NULL, 'PC supplémentaire', 1500, NULL, NULL, 'ABONNEMENT', 60),
    ('EQUIPEMENT', 'TABLETTE', NULL, 'Tablette supplémentaire', 1000, NULL, NULL, 'ABONNEMENT', 61),
    ('SUPPORT', 'PREMIUM', NULL, 'Support Premium', 4000, NULL, NULL, 'ABONNEMENT', 70),
    ('PRESTATION', 'MISE_EN_ROUTE', NULL, 'Mise en route', 30000, 'forfait', NULL, 'PRESTATION', 80),
    ('PRESTATION', 'FORMATION_DIRECTION', NULL, 'Formation direction (3 h)', 30000, 'séance', NULL, 'PRESTATION', 81),
    ('PRESTATION', 'FORMATION_EQUIPE', NULL, 'Formation équipe (1 h, 8 personnes)', 15000, 'séance', NULL, 'PRESTATION', 82),
    ('PRESTATION', 'SAISIE_PAPIER', NULL, 'Saisie depuis des dossiers papier', 1800, 'enfant', NULL, 'PRESTATION', 83),
    ('PRESTATION', 'INTERVENTION', NULL, 'Intervention sur site hors contrat', 9000, 'heure', NULL, 'PRESTATION', 84)
) AS seed (kind, code, tier, label, price, unit, includes, nature, position);
