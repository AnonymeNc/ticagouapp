-- Ordres aux instances (plan console, phase 5) : la console ne touche jamais Docker ; elle dépose des ordres que
-- `creche.sh appliquer` (cron de l'hôte, chaque minute) exécute sur une liste blanche, puis rend compte.

CREATE TABLE instance_order (
    id           UUID PRIMARY KEY,
    type         VARCHAR(14)  NOT NULL CHECK (type IN ('CREER', 'DEMO', 'SUPPRIMER_DEMO', 'ABONNEMENT', 'SUSPENDRE', 'REPRENDRE',
                                                       'METTRE_A_JOUR')),
    slug         VARCHAR(60)  NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,40}$'),
    -- Paramètres déjà validés (mêmes contrôles que creche.sh, qui les revérifie).
    params       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- A_CONFIRMER : proposé par la console (paiement reçu…) ; EN_ATTENTE : confirmé avec le code, l'hôte le prendra.
    status       VARCHAR(11)  NOT NULL CHECK (status IN ('A_CONFIRMER', 'EN_ATTENTE', 'EN_COURS', 'FAIT', 'ECHEC', 'ANNULE')),
    reason       VARCHAR(300),
    proposed_by  VARCHAR(60),
    confirmed_by VARCHAR(60),
    created_at   TIMESTAMPTZ  NOT NULL,
    confirmed_at TIMESTAMPTZ,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    output       VARCHAR(20000),
    -- Ce que l'ordre a produit, par ex. le lien de démarrage d'une crèche créée.
    result       JSONB
);
CREATE INDEX instance_order_status_idx ON instance_order (status, created_at);
CREATE INDEX instance_order_slug_idx ON instance_order (slug, created_at DESC);
-- Un seul ordre en cours ou en attente par crèche et par type : pas de doublon.
CREATE UNIQUE INDEX instance_order_open_uk ON instance_order (slug, type) WHERE status IN ('A_CONFIRMER', 'EN_ATTENTE', 'EN_COURS');

-- Les démonstrations sont des crèches à part : elles ne comptent ni comme clientes ni dans le revenu.
ALTER TABLE creche DROP CONSTRAINT creche_lifecycle_check;
ALTER TABLE creche ADD CONSTRAINT creche_lifecycle_check CHECK (lifecycle IN ('PROSPECT', 'CLIENT', 'RESILIE', 'DEMO'));
