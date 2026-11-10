# ADR 0022 — Rubriques masquées à la demande de la crèche

- **Statut** : acceptée
- **Date** : 10/11/2026
- **Complète** : [ADR 0021](0021-formule-unique.md) (formule unique)

## Contexte

Avec une seule formule (ADR 0021), tout Ti'Cagou est affiché à toutes les crèches. Certaines n'utilisent pas une partie
du logiciel : leur comptabilité est tenue ailleurs, elles ne gèrent pas les congés du personnel, n'ont pas de cuisine…
Elles demandent à l'éditeur de ne pas afficher ces onglets, pour simplifier l'écran de leurs équipes.

## Décision

**L'éditeur peut masquer des rubriques, crèche par crèche, à sa demande.** C'est un réglage d'affichage, pas une offre :
le prix ne change pas, rien n'est refusé ni effacé, et une rubrique masquée par erreur se réaffiche sans perte.

Rubriques masquables (`nc.ticagou.structure.Rubrique`) :

| Rubrique | Ce qui disparaît |
|---|---|
| `INSCRIPTIONS` | menu Inscriptions, onglet Contrat de la fiche enfant, Paramètres › Inscriptions, Formulaire en ligne, Barème |
| `FACTURATION` | menus Facturation et Statistiques, onglet Facturation de la fiche enfant, Paramètres › Financeurs, cartes d'impayés et indicateurs |
| `COMPTABILITE` | Paramètres › Comptabilité (comptes, exports CSV et FEC) |
| `PERSONNEL` | menu Personnel (planning, congés, heures), alertes et renforts du tableau de bord, réglages du personnel |
| `REPAS` | menu Repas, Paramètres › Repas, carte des repas du jour |
| `PORTAIL_PARENTS` | menu Messages, onglet Journée et portail, Paramètres › Portail parents, journée sur la tablette, absences et demandes signalées |

Dépendances : masquer les inscriptions masque la facturation (plus de contrats à facturer), qui masque la comptabilité.

Les informations de santé, le pointage, l'encadrement et les enfants ne sont jamais masquables (règles non négociables).

## Mise en œuvre

- Colonne `structure.hidden_sections` (V28), liste séparée par des virgules ; les noms inconnus sont ignorés.
- `GET /api/v1/rubriques` (poste bureau et tablette) ; le front lit `useRubriques()` et affiche tout tant que la réponse
  n'est pas arrivée ou en cas d'erreur, pour ne jamais masquer par erreur.
- Le guide « Premiers pas » saute les étapes des rubriques masquées.
- Réglé depuis la console de l'éditeur, fiche › Abonnement › Rubriques affichées (ordre `RUBRIQUES`, code redemandé),
  que l'hôte exécute avec `creche.sh rubriques <crèche> [--masquer A,B]` (aussi utilisable à la main). `creche.sh etat --json` rapporte les rubriques masquées à la
  console, qui les affiche sur la fiche.

## Conséquences

- La crèche ne règle pas elle-même ses rubriques : c'est une demande à l'éditeur, tracée dans les ordres de la console.
- Le serveur ne refuse rien : une API d'une rubrique masquée reste accessible selon le rôle. Le masquage n'est pas une
  mesure de sécurité, les droits restent ceux des rôles.
