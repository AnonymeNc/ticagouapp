-- Lot 1 de l'application parents : actualités de la crèche publiées sur le portail (ADR 0010, élément NEWS).
-- Une actualité s'adresse à toute la crèche ou à une section ; une pièce jointe (menu, projet pédagogique, affiche)
-- est facultative. Retirée, elle est effacée du portail ; sans date d'expiration, elle y reste 12 mois.
CREATE TABLE structure_news (
    id           UUID PRIMARY KEY,
    structure_id UUID          NOT NULL REFERENCES structure (id),
    section_id   UUID REFERENCES section (id),
    title        VARCHAR(120)  NOT NULL,
    body         VARCHAR(2000) NOT NULL,
    file_id      UUID REFERENCES stored_file (id),
    file_name    VARCHAR(150),
    published_by VARCHAR(120)  NOT NULL,
    published_at TIMESTAMPTZ   NOT NULL,
    expires_on   DATE,
    withdrawn_at TIMESTAMPTZ,
    withdrawn_by VARCHAR(120),
    CHECK ((file_id IS NULL) = (file_name IS NULL))
);
CREATE INDEX structure_news_idx ON structure_news (structure_id, published_at DESC);
