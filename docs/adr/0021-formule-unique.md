# ADR 0021 — Une seule formule, tout compris

- **Statut** : acceptée
- **Date** : 10/11/2026
- **Complétée par** : [ADR 0028](0028-postes-mobiles-et-abonnement.md) (applications Direction comprises, téléphones hors du compte des PC)

## Contexte

Le concurrent de référence vend une offre unique, tout compris.
Plusieurs formules compliquent la vente (« qu'est-ce qui n'est pas compris ? »), les démonstrations et le support, et
une crèche sans facturation ou sans portail perd l'essentiel de ce qui fait la valeur de Ti'Cagou.

## Décision

**Une seule formule : tout Ti'Cagou est compris** — enfants et familles, présences, encadrement, inscriptions et
contrats, facturation et aides, comptabilité, personnel, repas, portail parents, sauvegardes.

Ce qui différencie encore deux abonnements : le nombre de **places** (capacité agréée), de **PC** et de **tablettes**,
et l'échéance (ADR 0011, 0013). Le prix peut dépendre de la taille de la crèche ; le contenu, jamais.

Dans le logiciel :
- aucun contrôle par module : le contrôle des modifications ne porte que sur l'échéance de l'abonnement
  (`LicenseGuard`) ; menus, rubriques de Paramètres, onglets de la fiche enfant, cartes du tableau de bord sont
  affichés selon le rôle seulement ;
- la colonne `subscription.modules` reste en base mais n'est pas lue ;
- les mots « module Repas », « module Personnel »… nomment un **domaine fonctionnel**, pas une option payante.

## Conséquences

- Une seule démonstration, complète.
- La console de l'éditeur ne propose qu'une formule (code `COMPLET`, libellé « Ti'Cagou ») : contrats, avenants,
  barème, ordres d'abonnement et reporting sans formule ni module. Prix : **15 000 F HT par mois** quelle que soit la
  taille, **support rapide compris**, applications mobiles comprises ; voir
  [bareme-tarifaire.md](../commercial/bareme-tarifaire.md).
- La crèche peut faire masquer des rubriques qu'elle n'utilise pas ([ADR 0022](0022-rubriques-masquees.md)).
- Une crèche qui ne veut pas d'un domaine (portail parents, par exemple) ne l'active simplement pas : le portail reste
  désactivé tant que la direction ne l'ouvre pas.
