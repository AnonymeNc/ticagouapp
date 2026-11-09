-- Lot 3 : pré-inscription en ligne (ADR 0020).
ALTER TABLE enrollment_request
    ADD COLUMN source     VARCHAR(10) NOT NULL DEFAULT 'BUREAU' CHECK (source IN ('BUREAU', 'EN_LIGNE')),
    ADD COLUMN consent_at TIMESTAMPTZ;

-- Ouverture du formulaire public et texte d'accueil, par structure.
CREATE TABLE online_enrollment_settings (
    structure_id UUID PRIMARY KEY REFERENCES structure (id),
    enabled      BOOLEAN       NOT NULL DEFAULT FALSE,
    intro        VARCHAR(1000),
    updated_at   TIMESTAMPTZ   NOT NULL
);

-- Questions posées aux familles, datées comme les critères.
CREATE TABLE enrollment_question (
    id            UUID PRIMARY KEY,
    structure_id  UUID         NOT NULL REFERENCES structure (id),
    label         VARCHAR(200) NOT NULL,
    kind          VARCHAR(10)  NOT NULL CHECK (kind IN ('TEXTE', 'OUI_NON', 'CHOIX')),
    options       VARCHAR(500),
    required      BOOLEAN      NOT NULL DEFAULT FALSE,
    display_order INTEGER      NOT NULL DEFAULT 0,
    valid_from    DATE         NOT NULL,
    valid_to      DATE CHECK (valid_to IS NULL OR valid_to >= valid_from),
    created_at    TIMESTAMPTZ  NOT NULL,
    CHECK (kind <> 'CHOIX' OR options IS NOT NULL)
);
CREATE INDEX enrollment_question_structure_idx ON enrollment_question (structure_id);

-- Réponse : le libellé de la question est figé au moment de la demande.
CREATE TABLE enrollment_answer (
    request_id  UUID          NOT NULL REFERENCES enrollment_request (id) ON DELETE CASCADE,
    question_id UUID          NOT NULL REFERENCES enrollment_question (id),
    label       VARCHAR(200)  NOT NULL,
    value       VARCHAR(1000) NOT NULL,
    PRIMARY KEY (request_id, question_id)
);
