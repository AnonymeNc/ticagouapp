-- Lot 2 : demandes d'accueil occasionnel et retards annoncés, remontés à la crèche.
ALTER TABLE inbox DROP CONSTRAINT inbox_type_check;
ALTER TABLE inbox ADD CONSTRAINT inbox_type_check CHECK (type IN ('ABSENCE', 'MESSAGE', 'SIGNATURE', 'BOOKING'));
