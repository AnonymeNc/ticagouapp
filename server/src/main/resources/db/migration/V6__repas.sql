-- Phase 5 : repas et goûters comptés d'après les présences, menus de la semaine, régimes, clôture trimestrielle.

-- Heures du repas et du goûter : un repas est compté quand l'enfant est présent à cette heure.
ALTER TABLE structure
    ADD COLUMN lunch_time TIME NOT NULL DEFAULT '11:30',
    ADD COLUMN snack_time TIME NOT NULL DEFAULT '15:30',
    ADD CONSTRAINT structure_meal_times CHECK (snack_time > lunch_time);

-- ---------------------------------------------------------------------------
-- Régimes : ce que chacun exclut (étiquettes des plats, séparées par des virgules).
-- ---------------------------------------------------------------------------
CREATE TABLE diet (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id  UUID         NOT NULL REFERENCES structure (id),
    label         VARCHAR(60)  NOT NULL,
    excludes      VARCHAR(300) NOT NULL DEFAULT '',
    display_order INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX diet_label_uk ON diet (structure_id, lower(label));

INSERT INTO diet (structure_id, label, excludes, display_order, created_at, updated_at)
SELECT s.id, d.label, d.excludes, d.display_order, now(), now()
FROM structure s
CROSS JOIN (VALUES ('Sans porc', 'porc', 0),
                   ('Végétarien', 'viande,poisson,porc,crustacés,mollusques', 1),
                   ('Sans lactose', 'lait', 2),
                   ('Sans gluten', 'gluten', 3)) AS d (label, excludes, display_order);

-- Fiche santé : régime de la liste (le texte libre reste pour un régime absent de la liste),
-- texture des repas, panier repas fourni par la famille (PAI).
ALTER TABLE health_info
    ADD COLUMN diet_id      UUID REFERENCES diet (id) ON DELETE SET NULL,
    ADD COLUMN meal_texture VARCHAR(10) NOT NULL DEFAULT 'NORMALE'
        CHECK (meal_texture IN ('NORMALE', 'HACHEE', 'MIXEE')),
    ADD COLUMN packed_lunch BOOLEAN     NOT NULL DEFAULT FALSE;

UPDATE health_info h
SET diet_id = d.id
FROM diet d
WHERE d.structure_id = h.structure_id
  AND h.diet IS NOT NULL
  AND lower(trim(h.diet)) = lower(d.label);

-- ---------------------------------------------------------------------------
-- Menus de la semaine (lundi = week_start).
-- ---------------------------------------------------------------------------
CREATE TABLE menu_week (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    week_start   DATE         NOT NULL CHECK (extract(isodow FROM week_start) = 1),
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('BROUILLON', 'PUBLIE')),
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(170) NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT menu_week_uk UNIQUE (structure_id, week_start)
);

CREATE TABLE menu_item (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    week_id      UUID         NOT NULL REFERENCES menu_week (id) ON DELETE CASCADE,
    day_of_week  INTEGER      NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    course       VARCHAR(10)  NOT NULL CHECK (course IN ('ENTREE', 'PLAT', 'VARIANTE', 'LAITAGE', 'DESSERT', 'GOUTER')),
    diet_id      UUID REFERENCES diet (id) ON DELETE CASCADE,
    label        VARCHAR(200) NOT NULL,
    tags         VARCHAR(300) NOT NULL DEFAULT '',
    CONSTRAINT menu_item_variant CHECK ((course = 'VARIANTE') = (diet_id IS NOT NULL))
);
CREATE UNIQUE INDEX menu_item_uk ON menu_item (week_id, day_of_week, course,
    coalesce(diet_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- ---------------------------------------------------------------------------
-- Corrections manuelles du comptage et consignes du jour pour la cuisine.
-- ---------------------------------------------------------------------------
CREATE TABLE meal_adjustment (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    child_id     UUID         NOT NULL REFERENCES child (id),
    day          DATE         NOT NULL,
    meal         VARCHAR(10)  NOT NULL CHECK (meal IN ('REPAS', 'GOUTER')),
    served       BOOLEAN      NOT NULL,
    reason       VARCHAR(300) NOT NULL,
    author_id    UUID,
    author       VARCHAR(170) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT meal_adjustment_uk UNIQUE (child_id, day, meal)
);
CREATE INDEX meal_adjustment_day_idx ON meal_adjustment (structure_id, day);

CREATE TABLE meal_note (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id UUID         NOT NULL REFERENCES structure (id),
    child_id     UUID         NOT NULL REFERENCES child (id),
    day          DATE         NOT NULL,
    note         VARCHAR(300) NOT NULL,
    author       VARCHAR(170) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT meal_note_uk UNIQUE (child_id, day)
);

-- ---------------------------------------------------------------------------
-- Clôture trimestrielle : comptes figés, pointages et corrections refusés.
-- ---------------------------------------------------------------------------
CREATE TABLE meal_quarter_closure (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id  UUID         NOT NULL REFERENCES structure (id),
    year          INTEGER      NOT NULL,
    quarter       INTEGER      NOT NULL CHECK (quarter BETWEEN 1 AND 4),
    closed_at     TIMESTAMPTZ  NOT NULL,
    closed_by     VARCHAR(170) NOT NULL,
    reopened_at   TIMESTAMPTZ,
    reopened_by   VARCHAR(170),
    reopen_reason VARCHAR(300),
    CONSTRAINT meal_quarter_reopen CHECK (reopened_at IS NULL OR reopen_reason IS NOT NULL)
);
CREATE UNIQUE INDEX meal_quarter_closed_uk ON meal_quarter_closure (structure_id, year, quarter)
    WHERE reopened_at IS NULL;
