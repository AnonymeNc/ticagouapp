-- Dernier jour d'accès des parents, envoyé par le serveur de la crèche : fin d'abonnement plus la grâce, ou fin
-- d'évaluation. Au-delà, la structure est traitée comme suspendue et les sessions des parents s'arrêtent.
-- Vide : serveur de crèche qui ne l'envoie pas encore ; pas de limite.
ALTER TABLE portal_structure ADD COLUMN access_until DATE;
