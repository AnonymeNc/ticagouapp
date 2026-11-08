-- Identifiant de la crèche chez l'hébergeur (slug de deploy/creche.sh), repris du libellé du code de supervision que
-- `creche.sh creer` remplit avec ce slug. La console de l'éditeur (ADR 0017) rapproche ainsi supervision et hôte.
ALTER TABLE supervision_site ADD COLUMN slug VARCHAR(60);

UPDATE supervision_site s SET slug = c.label
FROM supervision_code c
WHERE c.site_id = s.id AND c.label ~ '^[a-z0-9][a-z0-9-]{1,40}$';

-- Historique des alertes lu par la console depuis une date.
CREATE INDEX supervision_alert_opened_idx ON supervision_alert (opened_at);
CREATE INDEX supervision_alert_closed_idx ON supervision_alert (closed_at) WHERE closed_at IS NOT NULL;
