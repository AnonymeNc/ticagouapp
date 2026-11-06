-- Phase 6 : facturation mensuelle, financeurs, absences, encaissements, relances.
-- Montants en francs CFP (BIGINT, sans décimale) ; taux en dixièmes de pour cent (200 = 20,0 %).

-- ---------------------------------------------------------------------------
-- Règles de facturation, datées.
-- ---------------------------------------------------------------------------
CREATE TABLE billing_rule (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id             UUID        NOT NULL REFERENCES structure (id),
    valid_from               DATE        NOT NULL,
    valid_to                 DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    extra_rate_mode          VARCHAR(10) NOT NULL CHECK (extra_rate_mode IN ('CONTRAT', 'FIXE')),
    extra_hourly_rate        BIGINT      NOT NULL DEFAULT 0 CHECK (extra_hourly_rate >= 0),
    extra_tolerance_minutes  INTEGER     NOT NULL CHECK (extra_tolerance_minutes BETWEEN 0 AND 60),
    extra_rounding_minutes   INTEGER     NOT NULL CHECK (extra_rounding_minutes IN (1, 5, 15, 30, 60)),
    overtime_fee             BIGINT      NOT NULL CHECK (overtime_fee >= 0),
    overtime_tolerance_minutes INTEGER   NOT NULL CHECK (overtime_tolerance_minutes BETWEEN 0 AND 60),
    sick_waiting_days        INTEGER     NOT NULL CHECK (sick_waiting_days BETWEEN 0 AND 10),
    leave_notice_days        INTEGER     NOT NULL CHECK (leave_notice_days BETWEEN 0 AND 90),
    payment_days             INTEGER     NOT NULL CHECK (payment_days BETWEEN 0 AND 60),
    total_rounding_step      INTEGER     NOT NULL CHECK (total_rounding_step IN (1, 10, 100)),
    total_rounding_mode      VARCHAR(10) NOT NULL CHECK (total_rounding_mode IN ('PROCHE', 'INFERIEUR', 'SUPERIEUR')),
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    version                  BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX billing_rule_structure_idx ON billing_rule (structure_id);

INSERT INTO billing_rule (structure_id, valid_from, extra_rate_mode, extra_tolerance_minutes, extra_rounding_minutes,
                          overtime_fee, overtime_tolerance_minutes, sick_waiting_days, leave_notice_days, payment_days,
                          total_rounding_step, total_rounding_mode, created_at, updated_at)
SELECT id, DATE '2024-01-01', 'CONTRAT', 10, 15, 1000, 10, 1, 15, 10, 1, 'PROCHE', now(), now() FROM structure;

-- Motifs d'absence : déductibles ou non, justificatif exigé, carence, préavis.
CREATE TABLE absence_reason (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id       UUID        NOT NULL REFERENCES structure (id),
    label              VARCHAR(80) NOT NULL,
    deductible         BOOLEAN     NOT NULL,
    proof_required     BOOLEAN     NOT NULL,
    waiting_days_apply BOOLEAN     NOT NULL,
    notice_applies     BOOLEAN     NOT NULL,
    display_order      INTEGER     NOT NULL DEFAULT 0,
    active             BOOLEAN     NOT NULL DEFAULT TRUE,
    version            BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX absence_reason_label_uk ON absence_reason (structure_id, lower(label));

INSERT INTO absence_reason (structure_id, label, deductible, proof_required, waiting_days_apply, notice_applies, display_order)
SELECT s.id, r.label, r.deductible, r.proof, r.waiting, r.notice, r.ord
FROM structure s
CROSS JOIN (VALUES ('Maladie avec certificat médical', TRUE, TRUE, TRUE, FALSE, 0),
                   ('Hospitalisation de l’enfant', TRUE, TRUE, FALSE, FALSE, 1),
                   ('Fermeture exceptionnelle de la structure', TRUE, FALSE, FALSE, FALSE, 2),
                   ('Éviction décidée par la direction', TRUE, FALSE, FALSE, FALSE, 3),
                   ('Congés de la famille prévenus à l’avance', TRUE, FALSE, FALSE, TRUE, 4),
                   ('Autre absence (non déduite)', FALSE, FALSE, FALSE, FALSE, 5))
    AS r (label, deductible, proof, waiting, notice, ord);

-- Taux de TGC par prestation, datés (0 % exonéré par défaut, à confirmer par l'expert-comptable).
CREATE TABLE tgc_rate (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID        NOT NULL REFERENCES structure (id),
    service      VARCHAR(25) NOT NULL
        CHECK (service IN ('FORFAIT', 'HEURES_COMPLEMENTAIRES', 'DEPASSEMENT', 'ADAPTATION', 'FRAIS_INSCRIPTION')),
    rate         INTEGER     NOT NULL CHECK (rate BETWEEN 0 AND 1000),
    exempt       BOOLEAN     NOT NULL,
    valid_from   DATE        NOT NULL,
    valid_to     DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT tgc_exempt_zero CHECK (NOT exempt OR rate = 0)
);

INSERT INTO tgc_rate (structure_id, service, rate, exempt, valid_from)
SELECT s.id, v.service, 0, TRUE, DATE '2024-01-01'
FROM structure s
CROSS JOIN (VALUES ('FORFAIT'), ('HEURES_COMPLEMENTAIRES'), ('DEPASSEMENT'), ('ADAPTATION'), ('FRAIS_INSCRIPTION')) AS v (service);

-- ---------------------------------------------------------------------------
-- Absences des enfants.
-- ---------------------------------------------------------------------------
CREATE TABLE child_absence (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    child_id     UUID         NOT NULL REFERENCES child (id),
    from_day     DATE         NOT NULL,
    to_day       DATE         NOT NULL CHECK (to_day >= from_day),
    reason_id    UUID         NOT NULL REFERENCES absence_reason (id),
    notified_on  DATE         NOT NULL,
    document_id  UUID REFERENCES child_document (id) ON DELETE SET NULL,
    note         VARCHAR(300),
    recorded_by  VARCHAR(170) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX child_absence_child_idx ON child_absence (child_id, from_day);

-- ---------------------------------------------------------------------------
-- Financeurs et aides accordées.
-- ---------------------------------------------------------------------------
CREATE TABLE funder (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    name           VARCHAR(120) NOT NULL,
    kind           VARCHAR(25)  NOT NULL CHECK (kind IN ('AIDE_FAMILLE', 'SUBVENTION_STRUCTURE')),
    category       VARCHAR(15)  NOT NULL CHECK (category IN ('PROVINCE', 'ORGANISME', 'EMPLOYEUR', 'COMMUNE', 'AUTRE')),
    calculation    VARCHAR(15)  NOT NULL CHECK (calculation IN ('MONTANT_FIXE', 'POURCENTAGE')),
    amount         BIGINT       NOT NULL DEFAULT 0 CHECK (amount >= 0),
    rate           INTEGER      NOT NULL DEFAULT 0 CHECK (rate BETWEEN 0 AND 1000),
    rate_base      VARCHAR(15)  NOT NULL DEFAULT 'FRAIS_GARDE' CHECK (rate_base IN ('FRAIS_GARDE', 'FORFAIT')),
    monthly_cap    BIGINT CHECK (monthly_cap IS NULL OR monthly_cap >= 0),
    priority       INTEGER      NOT NULL DEFAULT 0,
    valid_from     DATE         NOT NULL,
    valid_to       DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    convention_ref VARCHAR(80),
    contact        VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX funder_structure_idx ON funder (structure_id);

CREATE TABLE child_funding (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id    UUID        NOT NULL REFERENCES structure (id),
    child_id        UUID        NOT NULL REFERENCES child (id),
    funder_id       UUID        NOT NULL REFERENCES funder (id),
    from_day        DATE        NOT NULL,
    to_day          DATE CHECK (to_day IS NULL OR to_day >= from_day),
    beneficiary     VARCHAR(170),
    file_number     VARCHAR(60),
    amount_override BIGINT CHECK (amount_override IS NULL OR amount_override >= 0),
    rate_override   INTEGER CHECK (rate_override IS NULL OR rate_override BETWEEN 0 AND 1000),
    created_at      TIMESTAMPTZ NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0
);
CREATE INDEX child_funding_child_idx ON child_funding (child_id);

-- ---------------------------------------------------------------------------
-- Factures : une facture émise ne change plus (trigger) ; correction par avoir.
-- ---------------------------------------------------------------------------
CREATE TABLE invoice (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    number         VARCHAR(20)  NOT NULL,
    family_id      UUID         NOT NULL REFERENCES family (id),
    payer_name     VARCHAR(170) NOT NULL,
    payer_address  VARCHAR(400),
    month          DATE         NOT NULL CHECK (extract(day FROM month) = 1),
    issued_on      DATE         NOT NULL,
    due_on         DATE         NOT NULL,
    care_total     BIGINT       NOT NULL,
    tgc_total      BIGINT       NOT NULL,
    aid_total      BIGINT       NOT NULL,
    family_total   BIGINT       NOT NULL CHECK (family_total >= 0),
    rounding       BIGINT       NOT NULL DEFAULT 0,
    contract_text  VARCHAR(400),
    issued_by      VARCHAR(170) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT invoice_number_uk UNIQUE (structure_id, number)
);
CREATE INDEX invoice_family_idx ON invoice (family_id, month);
CREATE INDEX invoice_month_idx ON invoice (structure_id, month);

CREATE TABLE invoice_line (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID        NOT NULL REFERENCES structure (id),
    invoice_id  UUID         NOT NULL REFERENCES invoice (id),
    position    INTEGER      NOT NULL,
    child_id    UUID REFERENCES child (id),
    kind        VARCHAR(25)  NOT NULL CHECK (kind IN ('FORFAIT', 'ADAPTATION', 'FRAIS_INSCRIPTION',
                                                      'HEURES_COMPLEMENTAIRES', 'DEPASSEMENT', 'ABSENCE', 'AIDE')),
    label       VARCHAR(200) NOT NULL,
    detail      VARCHAR(500),
    quantity    VARCHAR(40),
    unit_price  BIGINT,
    amount      BIGINT       NOT NULL,
    tgc_rate    INTEGER      NOT NULL DEFAULT 0,
    tgc_amount  BIGINT       NOT NULL DEFAULT 0,
    funder_id   UUID REFERENCES funder (id)
);
CREATE INDEX invoice_line_invoice_idx ON invoice_line (invoice_id, position);

CREATE FUNCTION forbid_issued_change() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'La pièce % est émise : elle ne peut plus être modifiée (corrigez-la par un avoir)', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER invoice_immutable BEFORE UPDATE OR DELETE ON invoice
    FOR EACH ROW EXECUTE FUNCTION forbid_issued_change();
CREATE TRIGGER invoice_line_immutable BEFORE UPDATE OR DELETE ON invoice_line
    FOR EACH ROW EXECUTE FUNCTION forbid_issued_change();

CREATE TABLE credit_note (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    number       VARCHAR(20)  NOT NULL,
    invoice_id   UUID         NOT NULL REFERENCES invoice (id),
    issued_on    DATE         NOT NULL,
    reason       VARCHAR(300) NOT NULL,
    full_cancel  BOOLEAN      NOT NULL,
    family_total BIGINT       NOT NULL CHECK (family_total >= 0),
    aid_total    BIGINT       NOT NULL DEFAULT 0,
    issued_by    VARCHAR(170) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT credit_note_number_uk UNIQUE (structure_id, number)
);

CREATE TABLE credit_note_line (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    credit_note_id UUID         NOT NULL REFERENCES credit_note (id),
    position       INTEGER      NOT NULL,
    label          VARCHAR(200) NOT NULL,
    amount         BIGINT       NOT NULL
);

CREATE TRIGGER credit_note_immutable BEFORE UPDATE OR DELETE ON credit_note
    FOR EACH ROW EXECUTE FUNCTION forbid_issued_change();
CREATE TRIGGER credit_note_line_immutable BEFORE UPDATE OR DELETE ON credit_note_line
    FOR EACH ROW EXECUTE FUNCTION forbid_issued_change();

-- Événements affichés dans l'historique d'une facture (envoi noté, relance, avoir…).
CREATE TABLE invoice_event (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    invoice_id   UUID         NOT NULL REFERENCES invoice (id),
    kind         VARCHAR(20)  NOT NULL,
    label        VARCHAR(300) NOT NULL,
    author       VARCHAR(170) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX invoice_event_invoice_idx ON invoice_event (invoice_id, created_at);

-- ---------------------------------------------------------------------------
-- Demandes de règlement aux financeurs.
-- ---------------------------------------------------------------------------
CREATE TABLE funder_request (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    number       VARCHAR(20)  NOT NULL,
    funder_id    UUID         NOT NULL REFERENCES funder (id),
    month        DATE         NOT NULL,
    total        BIGINT       NOT NULL,
    sent_on      DATE,
    sent_by      VARCHAR(170),
    created_at   TIMESTAMPTZ  NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT funder_request_number_uk UNIQUE (structure_id, number)
);
CREATE INDEX funder_request_month_idx ON funder_request (structure_id, month);

CREATE TABLE funder_request_line (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id      UUID         NOT NULL REFERENCES structure (id),
    request_id        UUID         NOT NULL REFERENCES funder_request (id),
    child_id          UUID         NOT NULL REFERENCES child (id),
    invoice_id        UUID         NOT NULL REFERENCES invoice (id),
    beneficiary       VARCHAR(170),
    attendance_days   INTEGER      NOT NULL,
    amount            BIGINT       NOT NULL
);

-- ---------------------------------------------------------------------------
-- Encaissements et lettrage.
-- ---------------------------------------------------------------------------
CREATE TABLE payment (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id   UUID         NOT NULL REFERENCES structure (id),
    family_id      UUID REFERENCES family (id),
    funder_id      UUID REFERENCES funder (id),
    payer_name     VARCHAR(170) NOT NULL,
    paid_on        DATE         NOT NULL,
    amount         BIGINT       NOT NULL CHECK (amount > 0),
    method         VARCHAR(10)  NOT NULL CHECK (method IN ('VIREMENT', 'CHEQUE', 'ESPECES', 'CARTE')),
    reference      VARCHAR(80),
    bank           VARCHAR(80),
    receipt_number VARCHAR(20),
    recorded_by    VARCHAR(170) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    cancelled_at   TIMESTAMPTZ,
    cancelled_by   VARCHAR(170),
    cancel_reason  VARCHAR(300),
    CONSTRAINT payment_payer CHECK ((family_id IS NULL) <> (funder_id IS NULL)),
    CONSTRAINT payment_cancel CHECK (cancelled_at IS NULL OR cancel_reason IS NOT NULL)
);
CREATE INDEX payment_structure_idx ON payment (structure_id, paid_on);

CREATE TABLE payment_allocation (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id      UUID   NOT NULL REFERENCES structure (id),
    payment_id        UUID   NOT NULL REFERENCES payment (id),
    invoice_id        UUID REFERENCES invoice (id),
    funder_request_id UUID REFERENCES funder_request (id),
    amount            BIGINT NOT NULL CHECK (amount > 0),
    CONSTRAINT allocation_target CHECK ((invoice_id IS NULL) <> (funder_request_id IS NULL))
);
CREATE INDEX payment_allocation_invoice_idx ON payment_allocation (invoice_id);
CREATE INDEX payment_allocation_request_idx ON payment_allocation (funder_request_id);

CREATE TABLE reminder (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    invoice_id   UUID         NOT NULL REFERENCES invoice (id),
    level        INTEGER      NOT NULL CHECK (level BETWEEN 1 AND 3),
    created_on   DATE         NOT NULL,
    sent_on      DATE,
    author       VARCHAR(170) NOT NULL
);
CREATE INDEX reminder_invoice_idx ON reminder (invoice_id);
