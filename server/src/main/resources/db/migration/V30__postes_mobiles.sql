-- Applications mobiles (ADR 0028) : un téléphone ou une tablette de la direction est un poste MOBILE, enregistré et
-- révocable comme un PC, mais hors du compte des PC de l'abonnement. « app » garde l'application et sa version
-- (« Ti’Cagou Direction 1.0.0 · iOS »), affichées dans Paramètres › Tablettes et abonnement.
ALTER TABLE desktop_client ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'BUREAU' CHECK (kind IN ('BUREAU', 'MOBILE'));
ALTER TABLE desktop_client ADD COLUMN app VARCHAR(80);
