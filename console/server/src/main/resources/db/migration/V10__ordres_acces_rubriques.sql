-- Ordres pour piloter une crèche depuis la console sans passer par le serveur : rubriques masquées (ADR 0022),
-- mot de passe oublié (lien à usage unique), téléphone perdu, code d'activation du portail parents.
ALTER TABLE instance_order ALTER COLUMN type TYPE VARCHAR(20);
ALTER TABLE instance_order DROP CONSTRAINT instance_order_type_check;
ALTER TABLE instance_order ADD CONSTRAINT instance_order_type_check CHECK (type IN ('CREER', 'DEMO', 'SUPPRIMER_DEMO', 'ABONNEMENT', 'SUSPENDRE',
    'REPRENDRE', 'METTRE_A_JOUR', 'EXPORTER', 'SUPPRIMER', 'RUBRIQUES', 'TELEPHONE_PERDU', 'MOT_DE_PASSE', 'CODE_PORTAIL'));
