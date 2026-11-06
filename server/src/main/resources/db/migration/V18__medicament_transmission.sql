-- Médicament donné à la crèche, transmis aux parents (ADR 0014).
ALTER TABLE daily_entry DROP CONSTRAINT daily_entry_kind_check;
ALTER TABLE daily_entry ADD CONSTRAINT daily_entry_kind_check
    CHECK (kind IN ('REPAS', 'SIESTE', 'CHANGE', 'ACTIVITE', 'PHOTO', 'MOT', 'MEDICAMENT'));
