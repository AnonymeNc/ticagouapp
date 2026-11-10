# ADR 0015 — Formules et modules

- **Statut** : remplacée par l’[ADR 0021](0021-formule-unique.md) (une seule formule, tout compris)
- **Date** : 05/11/2026
- **Modifie** : ADR 0011 (modules), ADR 0013 (abonnement)

## Contexte

La base comprenait déjà inscriptions, contrats, statistiques, comptabilité et transmissions. L'offre minimale n'était donc
pas minimale, et une crèche voyait des paramètres pour des fonctions qu'elle n'a pas achetées.

## Décision

**La base** contient seulement ce qui est indispensable à une crèche : dossiers des enfants et des familles (santé,
autorisations, personnes autorisées), présences des enfants, présences du personnel, taux d'encadrement en temps réel,
tableau de bord, utilisateurs, tablettes, sauvegardes.

**Les modules**

| Module | Contenu | A besoin de |
|---|---|---|
| `INSCRIPTIONS` | Demandes, liste d'attente, capacité, critères, contrats, barème | — |
| `FACTURATION` | Factures, aides, encaissements, financeurs, statistiques | `INSCRIPTIONS` |
| `COMPTABILITE` | Comptes, export CSV et FEC | `FACTURATION` |
| `PERSONNEL` | Fiches, diplômes, planning, feuilles d'heures | — |
| `REPAS` | Menus, fiche cuisine, récapitulatif des aides | — |
| `PORTAIL_PARENTS` | Transmissions de la journée, application des parents, messages, signatures | — |

Les présences du personnel restent dans la base : le taux d'encadrement en dépend. Le module Personnel ajoute ce qui est
autour.

**Les formules** sont des assemblages de modules, pas une notion du logiciel, qui ne connaît que les modules :
- **Essentiel** : la base ;
- **Gestion** : la base, `INSCRIPTIONS`, `FACTURATION`, `PORTAIL_PARENTS` ;
- **Complet** : tous les modules.

Tout module s'ajoute à la carte. Un module entraîne ceux dont il a besoin (`LicenseModule.withRequired`).

**Effets**
- Sans le module : menu, rubriques de Paramètres, onglets de la fiche enfant, cartes du tableau de bord, rôles (Cuisine,
  Comptabilité) et droits affichés disparaissent. La tablette n'a plus le bouton Journée.
- Le serveur refuse les écritures (`module_absent`). Les lectures restent permises : une crèche qui réduit sa formule garde
  accès à ce qu'elle a saisi par l'API, et à l'export d'une famille.
- Paramètres › Tablettes et abonnement montre ce qui n'est pas compris.

## Conséquences

- Une crèche en Essentiel crée ses enfants directement, sans inscription ni contrat. Les enfants ne sont alors pas
  « attendus » : la tablette les liste tous, sans heure prévue.
- Sans contrat, l'encadrement prévisionnel et les retards ne sont pas calculés : ils relèvent du module Inscriptions.
- Les transmissions passent dans le module Portail parents : la tablette d'une crèche sans portail ne les propose plus.
