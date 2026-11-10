-- Rubriques masquées à la demande de la crèche (ADR 0022) : tout est compris dans la formule, mais une crèche qui ne
-- gère pas sa comptabilité ou ses congés peut demander à l'éditeur de ne pas afficher ces menus et onglets. Simple
-- affichage : rien n'est refusé ni effacé. Écrit par l'éditeur (creche.sh rubriques, ordre RUBRIQUES de la console).
-- « COMPTABILITE,PERSONNEL » ; nul ou vide : tout est affiché.
ALTER TABLE structure ADD COLUMN hidden_sections VARCHAR(200);
