-- Formules de facturation : forfaits mensuels nommés du barème (« Temps plein » 98 500 F, « Matins seulement »
-- 60 000 F, « 3 jours par semaine »…). Datées avec leur barème ; un contrat à planning fixe peut en choisir une à la
-- place du calcul à l'heure. Le tarif horaire de la tranche reste celui des heures complémentaires et de l'adaptation.

CREATE TABLE tariff_formula (
    schedule_id UUID        NOT NULL REFERENCES tariff_schedule (id) ON DELETE CASCADE,
    position    INTEGER     NOT NULL CHECK (position >= 0),
    label       VARCHAR(80) NOT NULL,
    monthly_fee BIGINT      NOT NULL CHECK (monthly_fee > 0),
    -- Libellés distincts dans un barème : vérifié par l'API (une contrainte gênerait la réécriture de la liste ordonnée).
    PRIMARY KEY (schedule_id, position)
);

-- Formule choisie, recopiée dans la version comme le reste du calcul (un barème modifié ne change pas un contrat signé).
ALTER TABLE contract_version ADD COLUMN formula_label VARCHAR(80);
