-- Relances préparées automatiquement (plan console, phase 4) : rien ne part sans un clic de l'éditeur.

-- Une version des règles de relance, avec ses dates de validité ; une nouvelle version ne touche pas le passé.
CREATE TABLE reminder_rule_set (
    id         UUID PRIMARY KEY,
    label      VARCHAR(80) NOT NULL,
    valid_from DATE        NOT NULL,
    valid_to   DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    created_at TIMESTAMPTZ NOT NULL
);

-- Un niveau : délai en jours par rapport à l'échéance (négatif : avant), modèle d'e-mail avec variables.
CREATE TABLE reminder_rule (
    set_id             UUID          NOT NULL REFERENCES reminder_rule_set (id) ON DELETE CASCADE,
    level              INTEGER       NOT NULL CHECK (level BETWEEN 0 AND 3),
    label              VARCHAR(60)   NOT NULL,
    days               INTEGER       NOT NULL CHECK (days BETWEEN -60 AND 365),
    active             BOOLEAN       NOT NULL,
    subject            VARCHAR(200)  NOT NULL,
    body               VARCHAR(6000) NOT NULL,
    copy_to_issuer     BOOLEAN       NOT NULL,
    attach_pdf         BOOLEAN       NOT NULL,
    propose_suspension BOOLEAN       NOT NULL,
    PRIMARY KEY (set_id, level)
);

-- Jours fériés de Nouvelle-Calédonie (paramétrables) : pas de préparation ces jours-là, ni le week-end.
CREATE TABLE public_holiday (
    day   DATE PRIMARY KEY,
    label VARCHAR(80) NOT NULL
);

-- Règles propres à un client : relances décalées (collectivité), suspendues (litige, échéancier), motif.
ALTER TABLE client ADD COLUMN reminder_shift_days INTEGER NOT NULL DEFAULT 0 CHECK (reminder_shift_days BETWEEN 0 AND 120);
ALTER TABLE client ADD COLUMN reminders_paused BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE client ADD COLUMN reminder_note VARCHAR(300);

-- Une relance par niveau et par facture, jamais deux ; plusieurs factures d'un client partagent un message.
CREATE TABLE reminder (
    id                UUID PRIMARY KEY,
    invoice_id        UUID        NOT NULL REFERENCES invoice (id),
    level             INTEGER     NOT NULL CHECK (level BETWEEN 0 AND 3),
    outbox_message_id UUID REFERENCES outbox_message (id),
    prepared_on       DATE        NOT NULL,
    days_late         INTEGER     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT reminder_level_uk UNIQUE (invoice_id, level)
);
CREATE INDEX reminder_message_idx ON reminder (outbox_message_id);

ALTER TABLE outbox_message ADD COLUMN reminder_level INTEGER CHECK (reminder_level BETWEEN 0 AND 3);
-- Relance : PDF des factures joints ou non, selon la règle au moment de la préparation.
ALTER TABLE outbox_message ADD COLUMN attach_pdf BOOLEAN NOT NULL DEFAULT TRUE;

-- --- Valeurs de départ (maquette « Paramètres › Relances ») --------------------------------------------------------

INSERT INTO reminder_rule_set (id, label, valid_from, created_at)
VALUES ('00000000-0000-0000-0000-00000000a001', 'Relances 2026', DATE '2026-01-01', now());

INSERT INTO reminder_rule (set_id, level, label, days, active, subject, body, copy_to_issuer, attach_pdf, propose_suspension) VALUES
('00000000-0000-0000-0000-00000000a001', 0, 'Rappel courtois', -3, FALSE,
 'Votre facture {numero} arrive à échéance',
 E'{bonjour}\n\nPetit rappel : {factures}\n\nSi le paiement est déjà parti, merci d''ignorer ce message.\n\nBien cordialement,\n{editeur}',
 FALSE, FALSE, FALSE),
('00000000-0000-0000-0000-00000000a001', 1, 'Relance 1', 7, TRUE,
 'Rappel de paiement — facture {numero}',
 E'{bonjour}\n\nSauf erreur de notre part, ce qui suit reste dû :\n{factures}\n\nTotal restant : {total}. Vous pouvez régler par virement ({paiement}).\nSi le paiement est en cours, merci d''ignorer ce message.\n\nBien cordialement,\n{editeur}',
 FALSE, TRUE, FALSE),
('00000000-0000-0000-0000-00000000a001', 2, 'Relance 2', 15, TRUE,
 'Deuxième relance — facture {numero}',
 E'{bonjour}\n\nMalgré notre précédent rappel, ce qui suit reste dû :\n{factures}\n\nTotal restant : {total}, en retard de {retard} jours. Merci de régler dès que possible ({paiement}) ou de nous dire si un point bloque.\n\nBien cordialement,\n{editeur}',
 TRUE, TRUE, FALSE),
('00000000-0000-0000-0000-00000000a001', 3, 'Relance 3', 30, TRUE,
 'Dernière relance avant suspension — facture {numero}',
 E'{bonjour}\n\nNous n''avons toujours pas reçu le règlement de :\n{factures}\n\nTotal restant : {total}, en retard de {retard} jours. Sans règlement ou nouvelle de votre part, nous devrons envisager de suspendre l''accès à Ti''Cagou ; nous préférons de loin en parler avec vous avant.\n\nBien cordialement,\n{editeur}',
 TRUE, TRUE, TRUE);

INSERT INTO public_holiday (day, label) VALUES
    (DATE '2026-01-01', 'Jour de l''an'), (DATE '2026-04-06', 'Lundi de Pâques'), (DATE '2026-05-01', 'Fête du travail'),
    (DATE '2026-05-08', 'Victoire 1945'), (DATE '2026-05-14', 'Ascension'), (DATE '2026-05-25', 'Lundi de Pentecôte'),
    (DATE '2026-07-14', 'Fête nationale'), (DATE '2026-08-15', 'Assomption'), (DATE '2026-09-24', 'Fête de la citoyenneté'),
    (DATE '2026-11-01', 'Toussaint'), (DATE '2026-11-11', 'Armistice'), (DATE '2026-12-25', 'Noël'),
    (DATE '2027-01-01', 'Jour de l''an'), (DATE '2027-03-29', 'Lundi de Pâques'), (DATE '2027-05-01', 'Fête du travail'),
    (DATE '2027-05-06', 'Ascension'), (DATE '2027-05-08', 'Victoire 1945'), (DATE '2027-05-17', 'Lundi de Pentecôte'),
    (DATE '2027-07-14', 'Fête nationale'), (DATE '2027-08-15', 'Assomption'), (DATE '2027-09-24', 'Fête de la citoyenneté'),
    (DATE '2027-11-01', 'Toussaint'), (DATE '2027-11-11', 'Armistice'), (DATE '2027-12-25', 'Noël');
