-- Vidéos de la journée, 30 s au plus, gardées 60 jours (ADR 0019). Le fichier est dans photo_file_id, comme une photo.
ALTER TABLE daily_entry DROP CONSTRAINT daily_entry_kind_check;
ALTER TABLE daily_entry ADD CONSTRAINT daily_entry_kind_check
    CHECK (kind IN ('REPAS', 'SIESTE', 'CHANGE', 'ACTIVITE', 'PHOTO', 'VIDEO', 'MOT', 'MEDICAMENT'));
CREATE INDEX daily_entry_video_idx ON daily_entry (day) WHERE kind = 'VIDEO' AND photo_file_id IS NOT NULL;
