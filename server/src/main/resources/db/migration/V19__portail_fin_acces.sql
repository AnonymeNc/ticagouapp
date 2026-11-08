-- Dernier jour d'accès des parents envoyé au portail (fin d'abonnement plus la grâce, ou fin d'évaluation).
-- Vide : pas encore envoyé ; la prochaine synchronisation le renvoie.
ALTER TABLE portal_settings ADD COLUMN access_until_sent DATE;
