-- Statistiques d'activité : capacité agréée datée, pour calculer les taux d'occupation des mois passés avec la
-- capacité de l'époque (un changement d'agrément ne fausse plus l'historique).
CREATE TABLE structure_capacity (
    structure_id UUID    NOT NULL REFERENCES structure (id) ON DELETE CASCADE,
    valid_from   DATE    NOT NULL,
    capacity     INTEGER NOT NULL CHECK (capacity > 0),
    PRIMARY KEY (structure_id, valid_from)
);

-- Capacité actuelle des structures existantes : connue depuis l'agrément (ou la création de la structure).
INSERT INTO structure_capacity (structure_id, valid_from, capacity)
SELECT id, coalesce(agrement_date, (created_at AT TIME ZONE 'Pacific/Noumea')::date), capacity FROM structure;

-- Chaque changement de capacité ouvre une période le jour même (heure de Nouméa).
CREATE FUNCTION record_structure_capacity() RETURNS trigger AS
$$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.capacity IS DISTINCT FROM OLD.capacity THEN
        INSERT INTO structure_capacity (structure_id, valid_from, capacity)
        VALUES (NEW.id, (now() AT TIME ZONE 'Pacific/Noumea')::date, NEW.capacity)
        ON CONFLICT (structure_id, valid_from) DO UPDATE SET capacity = excluded.capacity;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER structure_capacity_history AFTER INSERT OR UPDATE OF capacity ON structure
    FOR EACH ROW EXECUTE FUNCTION record_structure_capacity();
