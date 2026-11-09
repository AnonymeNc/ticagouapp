-- Suppression d'une crèche depuis la console : d'abord un export complet (EXPORTER), téléchargé sur l'ordinateur de
-- l'éditeur ; l'ordre SUPPRIMER n'est créé qu'après ce téléchargement et nomme l'export, que l'hôte revérifie.
ALTER TABLE instance_order DROP CONSTRAINT instance_order_type_check;
ALTER TABLE instance_order ADD CONSTRAINT instance_order_type_check CHECK (type IN ('CREER', 'DEMO', 'SUPPRIMER_DEMO', 'ABONNEMENT', 'SUSPENDRE',
                                                                                 'REPRENDRE', 'METTRE_A_JOUR', 'EXPORTER', 'SUPPRIMER'));
