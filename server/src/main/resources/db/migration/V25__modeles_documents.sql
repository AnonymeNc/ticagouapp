-- Lot 6 : modèles de documents (attestations, certificats, contrat de travail…), versionnés et datés.
CREATE TABLE document_template (
    id           UUID PRIMARY KEY,
    structure_id UUID         NOT NULL REFERENCES structure (id),
    subject      VARCHAR(10)  NOT NULL CHECK (subject IN ('ENFANT', 'PERSONNEL')),
    created_at   TIMESTAMPTZ  NOT NULL,
    retired_at   TIMESTAMPTZ
);
CREATE INDEX document_template_structure_idx ON document_template (structure_id);

-- Chaque modification crée une version : un document déjà remis reste reproductible.
CREATE TABLE document_template_version (
    template_id UUID          NOT NULL REFERENCES document_template (id),
    version     INTEGER       NOT NULL CHECK (version >= 1),
    title       VARCHAR(120)  NOT NULL,
    body        VARCHAR(20000) NOT NULL,
    valid_from  DATE          NOT NULL,
    author      VARCHAR(170)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (template_id, version)
);

-- Signataire et image de signature et cachet de la structure, apposés sur les documents.
CREATE TABLE document_settings (
    structure_id  UUID PRIMARY KEY REFERENCES structure (id),
    signatory     VARCHAR(120),
    stamp_file_id UUID REFERENCES stored_file (id),
    updated_at    TIMESTAMPTZ NOT NULL
);
